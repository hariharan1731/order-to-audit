package com.orderaudit.service;

import com.orderaudit.domain.Customer;
import com.orderaudit.repository.CustomerRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for customers: valid name, 2-digit state code, optional GSTIN matching the state, no duplicates. */
@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final AuditService audit;

    public CustomerService(CustomerRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public Customer create(String name, String gstin, String stateCode, String address) {
        String cleanName = PartyValidation.cleanName(name);
        String cleanState = PartyValidation.cleanStateCode(stateCode);
        String cleanGstin = PartyValidation.cleanGstin(gstin, cleanState);
        String cleanAddress = PartyValidation.cleanAddress(address);

        if (repository.existsByNameIgnoreCase(cleanName)) {
            throw new DuplicatePartyException("Customer name", cleanName);
        }
        if (cleanGstin != null && repository.existsByGstin(cleanGstin)) {
            throw new DuplicatePartyException("GSTIN", cleanGstin);
        }
        Customer saved = repository.save(new Customer(cleanName, cleanGstin, cleanState, cleanAddress));
        audit.record(com.orderaudit.security.CurrentUser.name(), "CUSTOMER_CREATED", "CUSTOMER", saved.getId(),
                cleanName + " (state " + cleanState + ")");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Customer> activeList() {
        return repository.findByActiveTrueOrderByNameAsc();
    }

    /** Every customer, active or not, by name: for pages that must still show a customer who was switched off. */
    @Transactional(readOnly = true)
    public List<Customer> all() {
        return repository.findAllByOrderByNameAsc();
    }

    /**
     * Corrects a customer's details. Past invoices keep the details they were raised with; the new state code
     * is used from the next invoice. Nothing is audited when nothing changed.
     */
    @Transactional
    public Customer update(long id, String name, String gstin, String stateCode, String address) {
        Customer party = repository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Customer " + id + " does not exist"));
        String cleanName = PartyValidation.cleanName(name);
        String cleanState = PartyValidation.cleanStateCode(stateCode);
        String cleanGstin = PartyValidation.cleanGstin(gstin, cleanState);
        String cleanAddress = PartyValidation.cleanAddress(address);

        if (repository.existsByNameIgnoreCaseAndIdNot(cleanName, id)) {
            throw new DuplicatePartyException("Customer name", cleanName);
        }
        if (cleanGstin != null && repository.existsByGstinAndIdNot(cleanGstin, id)) {
            throw new DuplicatePartyException("GSTIN", cleanGstin);
        }
        java.util.List<String> changes = new java.util.ArrayList<>();
        if (!party.getName().equals(cleanName)) {
            changes.add("name: " + party.getName() + " -> " + cleanName);
        }
        if (!java.util.Objects.equals(party.getGstin(), cleanGstin)) {
            changes.add("GSTIN: " + party.getGstin() + " -> " + cleanGstin);
        }
        if (!party.getStateCode().equals(cleanState)) {
            changes.add("state: " + party.getStateCode() + " -> " + cleanState);
        }
        if (!java.util.Objects.equals(party.getAddress(), cleanAddress)) {
            changes.add("address changed");
        }
        if (changes.isEmpty()) {
            return party;
        }
        party.update(cleanName, cleanGstin, cleanState, cleanAddress);
        repository.save(party);
        audit.record(com.orderaudit.security.CurrentUser.name(), "CUSTOMER_UPDATED", "CUSTOMER", id,
                String.join("; ", changes));
        return party;
    }

    /** Switches a customer off or on. Switching off keeps every old record and only stops new use. */
    @Transactional
    public void setActive(long id, boolean active) {
        Customer party = repository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Customer " + id + " does not exist"));
        if (party.isActive() == active) {
            return;
        }
        party.setActive(active);
        repository.save(party);
        audit.record(com.orderaudit.security.CurrentUser.name(),
                active ? "CUSTOMER_ACTIVATED" : "CUSTOMER_DEACTIVATED", "CUSTOMER", id, party.getName());
    }
}
