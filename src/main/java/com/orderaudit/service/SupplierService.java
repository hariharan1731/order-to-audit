package com.orderaudit.service;

import com.orderaudit.domain.Supplier;
import com.orderaudit.repository.SupplierRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for suppliers: valid name, 2-digit state code, optional GSTIN matching the state, no duplicates. */
@Service
public class SupplierService {

    private final SupplierRepository repository;
    private final AuditService audit;

    public SupplierService(SupplierRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public Supplier create(String name, String gstin, String stateCode, String address) {
        String cleanName = PartyValidation.cleanName(name);
        String cleanState = PartyValidation.cleanStateCode(stateCode);
        String cleanGstin = PartyValidation.cleanGstin(gstin, cleanState);
        String cleanAddress = PartyValidation.cleanAddress(address);

        if (repository.existsByNameIgnoreCase(cleanName)) {
            throw new DuplicatePartyException("Supplier name", cleanName);
        }
        if (cleanGstin != null && repository.existsByGstin(cleanGstin)) {
            throw new DuplicatePartyException("GSTIN", cleanGstin);
        }
        Supplier saved = repository.save(new Supplier(cleanName, cleanGstin, cleanState, cleanAddress));
        audit.record(com.orderaudit.security.CurrentUser.name(), "SUPPLIER_CREATED", "SUPPLIER", saved.getId(),
                cleanName + " (state " + cleanState + ")");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Supplier> activeList() {
        return repository.findByActiveTrueOrderByNameAsc();
    }

    /** Every supplier, active or not, by name: for pages that must still show a supplier who was switched off. */
    @Transactional(readOnly = true)
    public List<Supplier> all() {
        return repository.findAllByOrderByNameAsc();
    }

    /**
     * Corrects a supplier's details. Past invoices keep the details they were raised with; the new state code
     * is used from the next invoice. Nothing is audited when nothing changed.
     */
    @Transactional
    public Supplier update(long id, String name, String gstin, String stateCode, String address) {
        Supplier party = repository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Supplier " + id + " does not exist"));
        String cleanName = PartyValidation.cleanName(name);
        String cleanState = PartyValidation.cleanStateCode(stateCode);
        String cleanGstin = PartyValidation.cleanGstin(gstin, cleanState);
        String cleanAddress = PartyValidation.cleanAddress(address);

        if (repository.existsByNameIgnoreCaseAndIdNot(cleanName, id)) {
            throw new DuplicatePartyException("Supplier name", cleanName);
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
        audit.record(com.orderaudit.security.CurrentUser.name(), "SUPPLIER_UPDATED", "SUPPLIER", id,
                String.join("; ", changes));
        return party;
    }

    /** Switches a supplier off or on. Switching off keeps every old record and only stops new use. */
    @Transactional
    public void setActive(long id, boolean active) {
        Supplier party = repository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Supplier " + id + " does not exist"));
        if (party.isActive() == active) {
            return;
        }
        party.setActive(active);
        repository.save(party);
        audit.record(com.orderaudit.security.CurrentUser.name(),
                active ? "SUPPLIER_ACTIVATED" : "SUPPLIER_DEACTIVATED", "SUPPLIER", id, party.getName());
    }
}
