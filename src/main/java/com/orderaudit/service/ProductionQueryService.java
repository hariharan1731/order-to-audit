package com.orderaudit.service;

import com.orderaudit.domain.BomPlan;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PlannedLine;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.domain.RunLine;
import com.orderaudit.domain.RunOverhead;
import com.orderaudit.domain.RunStatus;
import com.orderaudit.report.QuantityFormat;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.PackConfigRepository;
import com.orderaudit.repository.ProductionRunRepository;
import com.orderaudit.repository.RunLineRepository;
import com.orderaudit.repository.RunOverheadRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only: the shapes the production screens need (names, readable quantities, the plan for a batch). */
@Service
public class ProductionQueryService {

    public record PackOption(long packConfigId, String productName, int packSizeGrams) {
    }

    public record RunSummary(long id, LocalDate runDate, String product, long expectedPackets, long goodPackets,
                             BigDecimal yieldVariancePct, boolean flagged, RunStatus status, BigDecimal costPerPacket) {
    }

    /** One material line of a plan: planned base quantity plus how to show it in kg / L / pcs. */
    public record PlanLine(long itemId, String name, long plannedBase, String plannedReadable, String plannedInput,
                           String unitLabel) {
    }

    public record Plan(long packConfigId, String product, BigDecimal batches, long expectedPackets, List<PlanLine> lines) {
    }

    public record RunLineView(String itemName, String plannedReadable, String actualReadable, BigDecimal variancePct,
                              boolean flagged, long lineCostPaise) {
    }

    public record RunDetail(RunSummary summary, long damagedPouches, BigDecimal tolerancePct, long materialPaise,
                            long overheadPaise, long totalPaise, String postedBy, List<RunLineView> lines,
                            List<RunOverhead> overheads) {
    }

    private final ProductionRunRepository runs;
    private final RunLineRepository runLines;
    private final RunOverheadRepository overheads;
    private final PackConfigRepository packConfigs;
    private final ItemRepository items;
    private final BomService bom;

    public ProductionQueryService(ProductionRunRepository runs, RunLineRepository runLines,
                                  RunOverheadRepository overheads, PackConfigRepository packConfigs,
                                  ItemRepository items, BomService bom) {
        this.runs = runs;
        this.runLines = runLines;
        this.overheads = overheads;
        this.packConfigs = packConfigs;
        this.items = items;
        this.bom = bom;
    }

    @Transactional(readOnly = true)
    public List<PackOption> packOptions() {
        List<PackOption> options = new ArrayList<>();
        for (PackConfig c : packConfigs.findAll()) {
            options.add(new PackOption(c.getId(), itemName(c.getFinishedItemId()), c.getPackSizeGrams()));
        }
        options.sort(Comparator.comparing(PackOption::productName));
        return options;
    }

    @Transactional(readOnly = true)
    public List<RunSummary> recent() {
        return runs.findAll().stream().sorted(Comparator.comparing(ProductionRun::getId).reversed()).limit(50)
                .map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Plan plan(long packConfigId, BigDecimal batches) {
        PackConfig config = packConfigs.findById(packConfigId).orElseThrow(
                () -> new IllegalArgumentException("Pack configuration " + packConfigId + " does not exist"));
        BomPlan plan = bom.plan(packConfigId, batches);
        List<PlanLine> lines = new ArrayList<>();
        for (PlannedLine p : plan.lines()) {
            Item item = items.findById(p.itemId()).orElseThrow();
            lines.add(new PlanLine(item.getId(), item.getName(), p.plannedQuantity(),
                    QuantityFormat.display(p.plannedQuantity(), item.getBaseUnit()),
                    com.orderaudit.web.UnitInput.toDisplay(p.plannedQuantity(), item.getBaseUnit()),
                    com.orderaudit.web.UnitInput.label(item.getBaseUnit())));
        }
        lines.sort(Comparator.comparing(PlanLine::name));
        return new Plan(packConfigId, itemName(config.getFinishedItemId()), batches, plan.expectedPackets(), lines);
    }

    @Transactional(readOnly = true)
    public RunDetail detail(long runId) {
        ProductionRun run = runs.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Production run " + runId + " does not exist"));
        List<RunLineView> views = new ArrayList<>();
        for (RunLine l : runLines.findByRunIdOrderByItemIdAsc(runId)) {
            Item item = items.findById(l.getItemId()).orElseThrow();
            views.add(new RunLineView(item.getName(), QuantityFormat.display(l.getPlannedQty(), item.getBaseUnit()),
                    QuantityFormat.display(l.getActualQty(), item.getBaseUnit()), l.getVariancePct(), l.isFlagged(),
                    l.getLineCostPaise()));
        }
        return new RunDetail(summary(run), run.getDamagedPouches(), run.getTolerancePct(), run.getTotalMaterialPaise(),
                run.getTotalOverheadPaise(), run.getTotalCostPaise(), run.getPostedBy(), views,
                overheads.findByRunId(runId));
    }

    private RunSummary summary(ProductionRun r) {
        PackConfig config = packConfigs.findById(r.getPackConfigId()).orElseThrow();
        return new RunSummary(r.getId(), r.getRunDate(), itemName(config.getFinishedItemId()), r.getExpectedPackets(),
                r.getActualGoodPackets(), r.getYieldVariancePct(), r.isYieldFlagged(), r.getStatus(),
                r.getCostPerPacket());
    }

    private String itemName(long itemId) {
        return items.findById(itemId).map(Item::getName).orElse("?");
    }
}
