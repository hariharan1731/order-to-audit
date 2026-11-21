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
}
