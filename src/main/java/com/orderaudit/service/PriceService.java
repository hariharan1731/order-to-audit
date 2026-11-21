package com.orderaudit.service;

import com.orderaudit.domain.CustomerPrice;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemPrice;
import com.orderaudit.domain.ItemType;
import com.orderaudit.repository.CustomerPriceRepository;
import com.orderaudit.repository.ItemPriceRepository;
import com.orderaudit.repository.ItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Price list: a default price per finished item, with an optional special price per customer. */
@Service
public class PriceService {

    private final ItemPriceRepository listPrices;
    private final CustomerPriceRepository customerPrices;
    private final ItemRepository items;
    private final AuditService audit;

    public PriceService(ItemPriceRepository listPrices, CustomerPriceRepository customerPrices, ItemRepository items,
                        AuditService audit) {
        this.audit = audit;
        this.listPrices = listPrices;
        this.customerPrices = customerPrices;
        this.items = items;
    }

    private void requireFinishedItem(long itemId) {
        Item item = items.findById(itemId).orElseThrow(
                () -> new IllegalArgumentException("Item " + itemId + " does not exist"));
        if (item.getItemType() != ItemType.FINISHED) {
            throw new IllegalArgumentException("Only finished products have a selling price");
        }
    }

    /** Set or change the default price (paise per packet, before tax). */
    @Transactional
    public void setListPrice(long itemId, long pricePaise) {
        requireFinishedItem(itemId);
        if (pricePaise < 0) {
            throw new IllegalArgumentException("Price must not be negative");
        }
        ItemPrice price = listPrices.findById(itemId).orElse(null);
        String before = price == null ? "none" : String.valueOf(price.getPricePaise());
        if (price == null) {
            listPrices.save(new ItemPrice(itemId, pricePaise));
        } else {
            price.setPricePaise(pricePaise);
        }
        audit.record(com.orderaudit.security.CurrentUser.name(), "PRICE_SET", "ITEM", itemId,
                "List price paise: " + before + " -> " + pricePaise);
    }

    @Transactional
    public void setCustomerPrice(long customerId, long itemId, long pricePaise) {
        requireFinishedItem(itemId);
        if (pricePaise < 0) {
            throw new IllegalArgumentException("Price must not be negative");
        }
        CustomerPrice price = customerPrices.findByCustomerIdAndItemId(customerId, itemId).orElse(null);
        String before = price == null ? "none" : String.valueOf(price.getPricePaise());
        if (price == null) {
            customerPrices.save(new CustomerPrice(customerId, itemId, pricePaise));
        } else {
            price.setPricePaise(pricePaise);
        }
        audit.record(com.orderaudit.security.CurrentUser.name(), "CUSTOMER_PRICE_SET", "CUSTOMER", customerId,
                "Item " + itemId + " special price paise: " + before + " -> " + pricePaise);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Long> listPriceOf(long itemId) {
        return listPrices.findById(itemId).map(ItemPrice::getPricePaise);
    }

    /** The customer's special price if there is one, else the default price, else an error. */
    @Transactional(readOnly = true)
    public long priceFor(long customerId, long itemId) {
        return customerPrices.findByCustomerIdAndItemId(customerId, itemId)
                .map(CustomerPrice::getPricePaise)
                .or(() -> listPrices.findById(itemId).map(ItemPrice::getPricePaise))
                .orElseThrow(() -> new IllegalArgumentException("No price is set for item " + itemId));
    }
}
