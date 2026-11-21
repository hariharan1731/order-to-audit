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
}
