package com.dunghaiquyen.ecommerce.modules.brand.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.config.CacheConfig;
import com.dunghaiquyen.ecommerce.modules.brand.dto.BrandCreateRequest;
import com.dunghaiquyen.ecommerce.modules.brand.dto.BrandResponse;
import com.dunghaiquyen.ecommerce.modules.brand.dto.BrandUpdateRequest;
import com.dunghaiquyen.ecommerce.modules.brand.entity.Brand;
import com.dunghaiquyen.ecommerce.modules.brand.entity.BrandStatus;
import com.dunghaiquyen.ecommerce.modules.brand.mapper.BrandMapper;
import com.dunghaiquyen.ecommerce.modules.brand.repository.BrandRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandService {

    private final BrandRepository brandRepository;
    private final BrandMapper brandMapper;

    public BrandService(BrandRepository brandRepository, BrandMapper brandMapper) {
        this.brandRepository = brandRepository;
        this.brandMapper = brandMapper;
    }

    @Cacheable(CacheConfig.BRANDS)
    @Transactional(readOnly = true)
    public List<BrandResponse> listActive() {
        return brandRepository.findAllByStatusOrderByNameAsc(BrandStatus.ACTIVE).stream()
                .map(brandMapper::toResponse)
                .toList();
    }

    @CacheEvict(value = CacheConfig.BRANDS, allEntries = true)
    @Transactional
    public BrandResponse create(BrandCreateRequest request) {
        if (brandRepository.existsBySlug(request.slug())) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }

        Brand brand = new Brand();
        brand.setCode(toCode(request.slug()));
        brand.setName(request.name().trim());
        brand.setSlug(request.slug());
        brand.setDescription(request.description());
        brand.setStatus(request.status() != null ? request.status() : BrandStatus.ACTIVE);

        try {
            brand = brandRepository.save(brand);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }
        return brandMapper.toResponse(brand);
    }

    @CacheEvict(value = CacheConfig.BRANDS, allEntries = true)
    @Transactional
    public void delete(UUID id) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
        try {
            brandRepository.delete(brand);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Không thể xóa thương hiệu đang được dùng bởi sản phẩm.");
        }
    }

    @CacheEvict(value = CacheConfig.BRANDS, allEntries = true)
    @Transactional
    public BrandResponse update(UUID id, BrandUpdateRequest request) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found"));

        if (request.slug() != null && brandRepository.existsBySlugAndIdNot(request.slug(), id)) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }

        if (request.name() != null) {
            brand.setName(request.name().trim());
        }
        if (request.slug() != null) {
            brand.setSlug(request.slug());
        }
        if (request.description() != null) {
            brand.setDescription(request.description());
        }
        if (request.status() != null) {
            brand.setStatus(request.status());
        }

        try {
            brand = brandRepository.save(brand);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleException("Slug already exists: " + request.slug());
        }
        return brandMapper.toResponse(brand);
    }

    private String toCode(String slug) {
        return slug.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }
}
