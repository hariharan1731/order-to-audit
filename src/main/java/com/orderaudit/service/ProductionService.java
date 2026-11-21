package com.orderaudit.service;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.BomPlan;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PlannedLine;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RunLine;
import com.orderaudit.domain.RunOverhead;
import com.orderaudit.domain.RunStatus;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.domain.Variance;
import com.orderaudit.repository.PackConfigRepository;
import com.orderaudit.repository.ProductionRunRepository;
import com.orderaudit.repository.RecipeRepository;
import com.orderaudit.repository.RunLineRepository;
import com.orderaudit.repository.RunOverheadRepository;
import com.orderaudit.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saving a production run is ONE transaction that does everything or nothing:
 *   plan from the BOM -> lock every stock row (ascending item id) -> cost each material at its weighted
 *   average -> compute variances -> save the run, its lines and overheads -> post the stock movements
 *   (raw/packaging out, finished goods in). If anything fails (e.g. not enough flour) all of it rolls back.
 * Costing rule: run cost = materials (actual quantity x weighted average cost) + overheads,
 * divided by GOOD packets only, so damaged pouches and over-use are absorbed by the good output.
 */
@Service
public class ProductionService {

    private final BomService bom;
    private final StockService stock;
    private final CostingService costing;
    private final SettingService settings;
    private final PackConfigRepository packConfigs;
    private final RecipeRepository recipes;
    private final ProductionRunRepository runs;
    private final RunLineRepository runLines;
    private final RunOverheadRepository overheadRows;
    private final StockMovementRepository movements;
    private final AuditService audit;

    public ProductionService(BomService bom, StockService stock, CostingService costing, SettingService settings,
                             PackConfigRepository packConfigs, RecipeRepository recipes,
                             ProductionRunRepository runs, RunLineRepository runLines,
                             RunOverheadRepository overheadRows, StockMovementRepository movements,
                             AuditService audit) {
        this.bom = bom;
        this.stock = stock;
        this.costing = costing;
        this.settings = settings;
        this.packConfigs = packConfigs;
        this.recipes = recipes;
        this.runs = runs;
        this.runLines = runLines;
        this.overheadRows = overheadRows;
        this.movements = movements;
        this.audit = audit;
    }

    /** One planned line after costing: what was planned, what was used, at what rate, and the variance. */
    private record Costed(PlannedLine line, long actualQty, BigDecimal unitCost, long lineCost, Variance variance) {
    }

    @Transactional
    public ProductionRun complete(long packConfigId, BigDecimal batchMultiplier, LocalDate runDate,
                                  long actualGoodPackets, long damagedPouches,
                                  List<ActualLine> actuals, List<OverheadInput> overheads, String postedBy) {
        // ---- validate ----
        if (runDate == null) {
            throw new IllegalArgumentException("Run date is required");
        }
        if (batchMultiplier == null || batchMultiplier.signum() <= 0
                || batchMultiplier.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("Batch multiplier must be above zero with at most 4 decimals");
        }
        if (actualGoodPackets < 0 || damagedPouches < 0) {
            throw new IllegalArgumentException("Actual packets and damaged pouches must not be negative");
        }
        if (postedBy == null || postedBy.isBlank()) {
            throw new IllegalArgumentException("postedBy is required");
        }
        List<ActualLine> actualList = actuals == null ? List.of() : actuals;
        List<OverheadInput> overheadList = overheads == null ? List.of() : overheads;
        for (OverheadInput o : overheadList) {
            if (o.description() == null || o.description().isBlank() || o.description().length() > 100) {
                throw new IllegalArgumentException("Each overhead needs a description (at most 100 characters)");
            }
            if (o.amountPaise() < 0) {
                throw new IllegalArgumentException("Overhead amounts must not be negative");
            }
        }

        // ---- plan ----
        PackConfig config = packConfigs.findById(packConfigId).orElseThrow(
                () -> new IllegalArgumentException("Pack configuration " + packConfigId + " does not exist"));
        BomPlan plan = bom.plan(packConfigId, batchMultiplier);
        Recipe recipe = recipes.findById(config.getRecipeId()).orElseThrow(
                () -> new IllegalStateException("Recipe " + config.getRecipeId() + " is missing"));
        BigDecimal tolerance = recipe.getTolerancePct() != null ? recipe.getTolerancePct() : settings.defaultTolerancePct();

        Map<Long, Long> actualByItem = new HashMap<>();
        for (ActualLine a : actualList) {
            if (a.actualQuantity() < 0) {
                throw new IllegalArgumentException("Actual quantities must not be negative");
            }
            if (actualByItem.put(a.itemId(), a.actualQuantity()) != null) {
                throw new IllegalArgumentException("Item " + a.itemId() + " is listed twice in the actuals");
            }
        }
        Set<Long> planned = new HashSet<>();
        for (PlannedLine line : plan.lines()) {
            planned.add(line.itemId());
        }
        for (Long itemId : actualByItem.keySet()) {
            if (!planned.contains(itemId)) {
                throw new IllegalArgumentException("Item " + itemId + " is not part of this product's BOM");
            }
        }

        // ---- lock every stock row we will touch, always in ascending item id (no deadlocks) ----
        TreeSet<Long> toLock = new TreeSet<>(planned);
        toLock.add(config.getFinishedItemId());
        for (Long itemId : toLock) {
            stock.lock(itemId);
        }

        // ---- cost + variance, per planned line (ascending item id) ----
        List<PlannedLine> ordered = plan.lines().stream()
                .sorted(Comparator.comparingLong(PlannedLine::itemId)).toList();
        List<Costed> computed = new ArrayList<>();
        long totalMaterial = 0;
        for (PlannedLine line : ordered) {
            long actualQty = actualByItem.getOrDefault(line.itemId(), line.plannedQuantity());
            BigDecimal unitCost = costing.weightedAverageCostPaise(line.itemId());
            if (unitCost == null) {
                if (actualQty > 0) {
                    throw new CostUnknownException(line.itemId());
                }
                unitCost = BigDecimal.ZERO.setScale(4);
            }
            long lineCost = CostMath.lineCostPaise(actualQty, unitCost);
            Variance variance = VarianceCalculator.compute(line.plannedQuantity(), actualQty, tolerance);
            totalMaterial += lineCost;
            computed.add(new Costed(line, actualQty, unitCost, lineCost, variance));
        }
        long totalOverhead = overheadList.stream().mapToLong(OverheadInput::amountPaise).sum();
        long totalCost = totalMaterial + totalOverhead;
        BigDecimal costPerPacket = actualGoodPackets > 0 ? CostMath.costPerPacket(totalCost, actualGoodPackets) : null;
        Variance yield = VarianceCalculator.compute(plan.expectedPackets(), actualGoodPackets, tolerance);

        // ---- save the run, lines and overheads ----
        ProductionRun run = runs.save(new ProductionRun(packConfigId, batchMultiplier, runDate,
                plan.expectedPackets(), actualGoodPackets, damagedPouches, tolerance, yield,
                totalMaterial, totalOverhead, costPerPacket, postedBy.trim()));
        for (Costed c : computed) {
            runLines.save(new RunLine(run.getId(), c.line().itemId(), c.line().source(), c.line().plannedQuantity(),
                    c.actualQty(), c.unitCost(), c.lineCost(), c.variance()));
        }
        for (OverheadInput o : overheadList) {
            overheadRows.save(new RunOverhead(run.getId(), o.description().trim(), o.amountPaise()));
        }

        // ---- post the stock movements ----
        for (Costed c : computed) {
            if (c.actualQty() > 0) {
                stock.post(c.line().itemId(), MovementType.PROD_ISSUE, -c.actualQty(), c.unitCost(),
                        SourceType.PRODUCTION_RUN, run.getId(), postedBy);
            }
        }
        if (actualGoodPackets > 0) {
            stock.post(config.getFinishedItemId(), MovementType.PROD_OUTPUT, actualGoodPackets, costPerPacket,
                    SourceType.PRODUCTION_RUN, run.getId(), postedBy);
        }
        audit.record(postedBy, "PRODUCTION_POSTED", "RUN", run.getId(),
                actualGoodPackets + " good packets, " + damagedPouches + " damaged pouches");
        return run;
    }

    /**
     * Undo a run by posting a reversal for each of its movements (nothing is deleted). Fails, changing
     * nothing, if the packets have already been sold and stock would go negative.
     */
    @Transactional
    public ProductionRun reverse(long runId, String postedBy) {
        ProductionRun run = runs.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Production run " + runId + " does not exist"));
        if (run.getStatus() == RunStatus.REVERSED) {
            throw new IllegalStateException("Production run " + runId + " has already been reversed");
        }
        List<StockMovement> toReverse = movements
                .findBySourceTypeAndSourceIdOrderByIdAsc(SourceType.PRODUCTION_RUN, runId).stream()
                .filter(m -> m.getMovementType() != MovementType.REVERSAL)
                .sorted(Comparator.comparingLong(StockMovement::getItemId))
                .toList();
        for (StockMovement m : toReverse) {
            stock.reverse(m.getId(), postedBy);
        }
        run.markReversed();
        audit.record(postedBy, "PRODUCTION_REVERSED", "RUN", runId, null);
        return runs.save(run);
    }
}
