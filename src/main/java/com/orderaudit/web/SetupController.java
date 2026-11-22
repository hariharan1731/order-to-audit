package com.orderaudit.web;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.PackBasis;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.service.DuplicateRecordException;
import com.orderaudit.service.ItemService;
import com.orderaudit.service.PackConfigService;
import com.orderaudit.service.RecipeService;
import com.orderaudit.service.SetupQueryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Recipes and pack configurations: the "how we make and pack it" sheet, plus forms to create them.
 * Quantities are typed in kg / L / pieces and converted exactly to grams / ml.
 */
@Controller
public class SetupController {

    /** An item for a drop-down, with the unit label shown next to its quantity box. */
    public record Choice(long id, String name, String unit) {
    }

    private final SetupQueryService setup;
    private final RecipeService recipeService;
    private final PackConfigService packConfigService;
    private final ItemService itemService;
    private final ItemRepository items;

    public SetupController(SetupQueryService setup, RecipeService recipeService, PackConfigService packConfigService,
                           ItemService itemService, ItemRepository items) {
        this.setup = setup;
        this.recipeService = recipeService;
        this.packConfigService = packConfigService;
        this.itemService = itemService;
        this.items = items;
    }

    @GetMapping("/admin/setup")
    public String show(Model model) {
        model.addAttribute("recipes", setup.all());
        model.addAttribute("rawItems", choices(ItemType.RAW));
        model.addAttribute("packagingItems", choices(ItemType.PACKAGING));
        model.addAttribute("finishedItems", choices(ItemType.FINISHED));
        model.addAttribute("recipeRows", new int[] {0, 1, 2, 3, 4});
        model.addAttribute("packRows", new int[] {0, 1, 2});
        return "admin/setup";
    }

    private List<Choice> choices(ItemType type) {
        List<Choice> list = new ArrayList<>();
        for (Item i : itemService.activeItemsOfType(type)) {
            list.add(new Choice(i.getId(), i.getName(), UnitInput.label(i.getBaseUnit())));
        }
        return list;
    }

    @PostMapping("/admin/setup/recipes")
    public String createRecipe(@RequestParam String name, @RequestParam String bulkYieldKg,
                               @RequestParam(required = false) String tolerancePct,
                               @RequestParam(name = "itemId", required = false) List<String> itemIds,
                               @RequestParam(name = "quantity", required = false) List<String> quantities,
                               RedirectAttributes redirect) {
        try {
            List<RecipeLineSpec> specs = new ArrayList<>();
            for (int i = 0; itemIds != null && i < itemIds.size(); i++) {
                String id = itemIds.get(i);
                String qty = quantities != null && i < quantities.size() ? quantities.get(i) : "";
                if (id == null || id.isBlank() || qty == null || qty.isBlank()) {
                    continue;
                }
                Item item = items.findById(Long.parseLong(id)).orElseThrow(
                        () -> new IllegalArgumentException("Unknown item"));
                specs.add(new RecipeLineSpec(item.getId(), UnitInput.toBase(qty, item.getBaseUnit())));
            }
            BigDecimal tolerance = tolerancePct == null || tolerancePct.isBlank() ? null : new BigDecimal(tolerancePct.trim());
            recipeService.create(name, UnitInput.toBase(bulkYieldKg, Unit.GRAM), tolerance, specs);
            redirect.addFlashAttribute("message", "Recipe saved.");
        } catch (DuplicateRecordException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/setup";
    }

    @PostMapping("/admin/setup/packs")
    public String createPack(@RequestParam long finishedItemId, @RequestParam long recipeId,
                             @RequestParam int packSizeGrams, @RequestParam int packetsPerCarton,
                             @RequestParam(name = "itemId", required = false) List<String> itemIds,
                             @RequestParam(name = "quantity", required = false) List<String> quantities,
                             @RequestParam(name = "basis", required = false) List<String> bases,
                             RedirectAttributes redirect) {
        try {
            List<PackLineSpec> specs = new ArrayList<>();
            for (int i = 0; itemIds != null && i < itemIds.size(); i++) {
                String id = itemIds.get(i);
                String qty = quantities != null && i < quantities.size() ? quantities.get(i) : "";
                if (id == null || id.isBlank() || qty == null || qty.isBlank()) {
                    continue;
                }
                PackBasis basis = bases != null && i < bases.size() ? PackBasis.valueOf(bases.get(i)) : PackBasis.PER_PACKET;
                specs.add(new PackLineSpec(Long.parseLong(id), Long.parseLong(qty.trim()), basis));
            }
            packConfigService.create(finishedItemId, recipeId, packSizeGrams, packetsPerCarton, specs);
            redirect.addFlashAttribute("message", "Pack configuration saved.");
        } catch (DuplicateRecordException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/setup";
    }
}
