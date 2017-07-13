package org.broadleafcommerce.profile.core.service;

import org.broadleafcommerce.profile.core.domain.SavedPayment;

import java.util.List;

/**
 * @author Jacob Mitash
 */
public interface CustomerSavedPaymentService {

    void saveSavedPayment(SavedPayment savedPayment);

    List<SavedPayment> readSavedPaymentsByCustomerId(Long customerId);

    void deleteSavedPayment(SavedPayment savedPayment);

    SavedPayment create();
}
