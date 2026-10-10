package com.dunghaiquyen.ecommerce.modules.address.service;

import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.address.dto.AddressCreateRequest;
import com.dunghaiquyen.ecommerce.modules.address.dto.AddressResponse;
import com.dunghaiquyen.ecommerce.modules.address.dto.AddressUpdateRequest;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service address book - every method is scoped to the caller's own
 * userId (never trusts an addressId alone), same ownership-check pattern
 * already used for orders/carts.
 *
 * <p>"Default" invariant is protected by the 1A partial unique index. The
 * service clears and flushes the previous default before inserting/promoting
 * the new row so Hibernate cannot violate the index through insert-first flush
 * ordering.
 *
 * <p>Deleting the current default address does NOT auto-promote another
 * address to default (self-chosen rule, see class README/report) - silently
 * picking some other address as the new default on the user's behalf would
 * be a surprising side effect; leaving "no default" until the user picks one
 * explicitly is the safer, more predictable behavior.
 */
@Service
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    public AddressService(AddressRepository addressRepository, UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> listMine(UUID userId) {
        return addressRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AddressResponse create(UUID userId, AddressCreateRequest request) {
        boolean makeDefault = request.isDefault() != null && request.isDefault();
        if (makeDefault) {
            unsetCurrentDefaults(userId);
        }

        Address address = new Address();
        address.setUser(userRepository.getReferenceById(userId));
        address.setReceiverName(request.receiverName().trim());
        address.setPhone(request.phone().trim());
        address.setProvince(request.province().trim());
        address.setDistrict(request.district() != null ? request.district().trim() : null);
        address.setWard(request.ward().trim());
        address.setAddressLine(request.addressLine().trim());
        address.setDefault(makeDefault);

        return toResponse(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse update(UUID userId, UUID addressId, AddressUpdateRequest request) {
        Address address = findOwned(userId, addressId);
        if (request.receiverName() != null) {
            address.setReceiverName(request.receiverName().trim());
        }
        if (request.phone() != null) {
            address.setPhone(request.phone().trim());
        }
        if (request.province() != null) {
            address.setProvince(request.province().trim());
        }
        if (request.district() != null) {
            address.setDistrict(request.district().trim());
        }
        if (request.ward() != null) {
            address.setWard(request.ward().trim());
        }
        if (request.addressLine() != null) {
            address.setAddressLine(request.addressLine().trim());
        }
        return toResponse(addressRepository.save(address));
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        Address address = findOwned(userId, addressId);
        addressRepository.delete(address);
    }

    @Transactional
    public AddressResponse setDefault(UUID userId, UUID addressId) {
        Address address = findOwned(userId, addressId);
        if (!address.isDefault()) {
            unsetCurrentDefaults(userId);
            address.setDefault(true);
            address = addressRepository.save(address);
        }
        return toResponse(address);
    }

    private void unsetCurrentDefaults(UUID userId) {
        List<Address> currentDefaults = addressRepository.findAllByUserIdAndIsDefaultTrue(userId);
        currentDefaults.forEach(a -> a.setDefault(false));
        addressRepository.saveAllAndFlush(currentDefaults);
    }

    private Address findOwned(UUID userId, UUID addressId) {
        return addressRepository.findById(addressId)
                .filter(a -> a.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));
    }

    private AddressResponse toResponse(Address address) {
        return new AddressResponse(
                address.getId(),
                address.getReceiverName(),
                address.getPhone(),
                address.getProvince(),
                address.getDistrict(),
                address.getWard(),
                address.getAddressLine(),
                address.isDefault(),
                address.getCreatedAt());
    }
}
