package com.dunghaiquyen.ecommerce.modules.user.api;

import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import java.util.List;
import java.util.UUID;

/** Transitional Identity API for JPA references while cross-context entity relations still exist. */
public interface UserReferenceQuery {

    User getReference(UUID userId);

    List<User> findActiveAdmins();
}
