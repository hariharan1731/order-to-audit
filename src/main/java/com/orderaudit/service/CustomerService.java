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

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
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
        return repository.save(new Customer(cleanName, cleanGstin, cleanState, cleanAddress));
    }

    @Transactional(readOnly = true)
    public List<Customer> activeList() {
        return repository.findByActiveTrueOrderByNameAsc();
    }
}
