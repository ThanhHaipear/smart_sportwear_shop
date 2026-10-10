package com.dunghaiquyen.ecommerce.modules.address.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractAuditEntity;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "addresses")
public class Address extends AbstractAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "receiver_name", nullable = false, length = 150)
    private String receiverName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "province_code", length = 30)
    private String provinceCode;

    @Column(name = "province_name", nullable = false, length = 100)
    private String provinceName;

    /** Vietnam dropped this administrative tier in the 2025 reform - kept nullable for legacy rows. */
    @Column(name = "district_code", length = 30)
    private String districtCode;

    @Column(name = "district_name", length = 100)
    private String districtName;

    @Column(name = "ward_code", length = 30)
    private String wardCode;

    @Column(name = "ward_name", nullable = false, length = 100)
    private String wardName;

    @Column(name = "address_line", nullable = false, length = 255)
    private String addressLine;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    public String getProvince() {
        return provinceName;
    }

    public void setProvince(String province) {
        this.provinceName = province;
    }

    public String getDistrict() {
        return districtName;
    }

    public void setDistrict(String district) {
        this.districtName = district;
    }

    public String getWard() {
        return wardName;
    }

    public void setWard(String ward) {
        this.wardName = ward;
    }
}
