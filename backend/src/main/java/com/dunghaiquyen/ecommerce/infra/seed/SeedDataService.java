package com.dunghaiquyen.ecommerce.infra.seed;

import com.dunghaiquyen.ecommerce.config.AppSeedProperties;
import com.dunghaiquyen.ecommerce.modules.address.entity.Address;
import com.dunghaiquyen.ecommerce.modules.address.repository.AddressRepository;
import com.dunghaiquyen.ecommerce.modules.brand.entity.Brand;
import com.dunghaiquyen.ecommerce.modules.brand.entity.BrandStatus;
import com.dunghaiquyen.ecommerce.modules.brand.repository.BrandRepository;
import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.entity.CartItem;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartItemRepository;
import com.dunghaiquyen.ecommerce.modules.cart.repository.CartRepository;
import com.dunghaiquyen.ecommerce.modules.category.entity.Category;
import com.dunghaiquyen.ecommerce.modules.category.entity.CategoryStatus;
import com.dunghaiquyen.ecommerce.modules.category.repository.CategoryRepository;
import com.dunghaiquyen.ecommerce.modules.order.dto.CreateOrderRequest;
import com.dunghaiquyen.ecommerce.modules.order.dto.OrderResponse;
import com.dunghaiquyen.ecommerce.modules.order.dto.UpdateOrderStatusRequest;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.order.service.OrderService;
import com.dunghaiquyen.ecommerce.modules.product.entity.Gender;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductImage;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.product.entity.VariantStatus;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductImageRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductRepository;
import com.dunghaiquyen.ecommerce.modules.product.repository.ProductVariantRepository;
import com.dunghaiquyen.ecommerce.modules.review.dto.CreateReviewRequest;
import com.dunghaiquyen.ecommerce.modules.review.dto.UpdateReviewStatusRequest;
import com.dunghaiquyen.ecommerce.modules.review.entity.ReviewStatus;
import com.dunghaiquyen.ecommerce.modules.review.repository.ProductReviewRepository;
import com.dunghaiquyen.ecommerce.modules.review.service.ReviewService;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserStatus;
import com.dunghaiquyen.ecommerce.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class SeedDataService {

    private static final Logger log = LoggerFactory.getLogger(SeedDataService.class);

    public static final String DEMO_ADMIN_EMAIL = "admin@smartsportswear.local";
    public static final String DEMO_SALES_EMAIL = "sales@smartsportswear.local";
    public static final String DEMO_WAREHOUSE_EMAIL = "warehouse@smartsportswear.local";
    public static final String DEMO_CUSTOMER_ONE_EMAIL = "customer1@smartsportswear.local";
    public static final String DEMO_CUSTOMER_TWO_EMAIL = "customer2@smartsportswear.local";
    public static final String DEMO_CUSTOMER_THREE_EMAIL = "customer3@smartsportswear.local";

    private static final String DEFAULT_DEMO_PASSWORD = "Password123";
    private static final List<String> SEED_USER_EMAILS = List.of(
            DEMO_ADMIN_EMAIL,
            DEMO_SALES_EMAIL,
            DEMO_WAREHOUSE_EMAIL,
            DEMO_CUSTOMER_ONE_EMAIL,
            DEMO_CUSTOMER_TWO_EMAIL,
            DEMO_CUSTOMER_THREE_EMAIL);
    private static final List<String> SEED_PRODUCT_SLUGS = List.of(
            "seed-running-tee",
            "seed-football-boots",
            "seed-training-socks",
            "seed-compression-tank",
            "seed-windbreaker");
    private static final List<String> SEED_VARIANT_SKUS = List.of(
            "SEED-RUN-TEE-BLK-M",
            "SEED-RUN-TEE-WHT-L",
            "SEED-BOOT-BLK-42",
            "SEED-BOOT-RED-43",
            "SEED-SOCK-WHT-39",
            "SEED-SOCK-BLK-42",
            "SEED-TANK-BLU-M",
            "SEED-TANK-GRY-L",
            "SEED-WIND-BLK-M",
            "SEED-WIND-BLK-L");
    private static final List<String> SEED_ORDER_NOTES = List.of(
            "[SEED] confirmed-cod",
            "[SEED] delivered-cod");

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository imageRepository;
    private final AddressRepository addressRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PasswordEncoder passwordEncoder;
    private final AppSeedProperties seedProperties;
    private final ProductReviewRepository productReviewRepository;
    private final ReviewService reviewService;

    public SeedDataService(
            UserRepository userRepository,
            CategoryRepository categoryRepository,
            BrandRepository brandRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            ProductImageRepository imageRepository,
            AddressRepository addressRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            OrderRepository orderRepository,
            OrderService orderService,
            PasswordEncoder passwordEncoder,
            AppSeedProperties seedProperties,
            ProductReviewRepository productReviewRepository,
            ReviewService reviewService) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.imageRepository = imageRepository;
        this.addressRepository = addressRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.productReviewRepository = productReviewRepository;
        this.reviewService = reviewService;
        this.passwordEncoder = passwordEncoder;
        this.seedProperties = seedProperties;
    }

    public record SeedSummary(long users, long products, long variants, long orders) {
    }

    public SeedSummary seed() {
        User admin = ensureUser(DEMO_ADMIN_EMAIL, "Demo Admin", "0900000001", UserRole.ADMIN);
        ensureUser(DEMO_SALES_EMAIL, "Demo Sales", "0900000002", UserRole.SALES_STAFF);
        ensureUser(DEMO_WAREHOUSE_EMAIL, "Demo Warehouse", "0900000003", UserRole.WAREHOUSE_STAFF);
        User customerOne = ensureUser(DEMO_CUSTOMER_ONE_EMAIL, "Demo Customer One", "0900000011", UserRole.CUSTOMER);
        User customerTwo = ensureUser(DEMO_CUSTOMER_TWO_EMAIL, "Demo Customer Two", "0900000012", UserRole.CUSTOMER);
        User customerThree =
                ensureUser(DEMO_CUSTOMER_THREE_EMAIL, "Demo Customer Three", "0900000013", UserRole.CUSTOMER);

        Category running = ensureCategory("running", "Running", "Ao quan va phu kien cho chay bo.");
        Category football = ensureCategory("football", "Football", "Do bong da cho tap luyen va thi dau.");
        Category training = ensureCategory("training", "Training", "Do tap gym va luyen tap da mon.");

        Brand nike = ensureBrand("nike", "Nike", "Thuong hieu cho dong san pham chay bo va tap luyen.");
        Brand adidas = ensureBrand("adidas", "Adidas", "Thuong hieu the thao pho bien cho bong da.");
        Brand underArmour = ensureBrand(
                "under-armour", "Under Armour", "Thuong hieu noi bat cho training va gym.");

        Product runningTee = ensureProduct(
                "seed-running-tee",
                "Ao Chay Bo Dry-Fit",
                running,
                nike,
                "Ao thun thoang khi cho buoi chay hang ngay.",
                "Ao chay bo chat lieu dry-fit, mac nhe va thuan tien cho training.",
                Gender.MEN,
                "running",
                ProductStatus.ACTIVE,
                true);
        Product footballBoots = ensureProduct(
                "seed-football-boots",
                "Giay Da Bong Control Pro",
                football,
                adidas,
                "Giay da bong om chan, bam san tot.",
                "Giay da bong cho tap luyen va thi dau san co nhan tao.",
                Gender.UNISEX,
                "football",
                ProductStatus.ACTIVE,
                true);
        Product trainingSocks = ensureProduct(
                "seed-training-socks",
                "Tat Tap Training Grip",
                training,
                underArmour,
                "Tat tap om chan, chong truot.",
                "Tat tap co do bam tot, phu hop gym, training va cardio.",
                Gender.UNISEX,
                "training",
                ProductStatus.ACTIVE,
                false);
        Product compressionTank = ensureProduct(
                "seed-compression-tank",
                "Ao Tank Compression Flex",
                training,
                underArmour,
                "Ao tank compression danh cho tap gym.",
                "Ao tank co do co gian tot, giu co bap on dinh khi tap.",
                Gender.MEN,
                "training",
                ProductStatus.ACTIVE,
                false);
        Product windbreaker = ensureProduct(
                "seed-windbreaker",
                "Ao Khoac Gio Lightweight",
                running,
                nike,
                "Ao khoac gio nhe cho buoi sang som.",
                "Ao khoac gon nhe, can gio, dung khi warm-up hoac chay troi lanh.",
                Gender.UNISEX,
                "running",
                ProductStatus.ACTIVE,
                false);

        ProductVariant runningTeeM = ensureVariant(
                runningTee,
                "SEED-RUN-TEE-BLK-M",
                "M",
                "Black",
                390000,
                490000,
                30,
                VariantStatus.ACTIVE);
        ensureVariant(runningTee, "SEED-RUN-TEE-WHT-L", "L", "White", 390000, 490000, 18, VariantStatus.ACTIVE);

        ProductVariant footballBoots42 = ensureVariant(
                footballBoots,
                "SEED-BOOT-BLK-42",
                "42",
                "Black",
                1490000,
                1690000,
                20,
                VariantStatus.ACTIVE);
        ensureVariant(
                footballBoots, "SEED-BOOT-RED-43", "43", "Red", 1490000, 1690000, 12, VariantStatus.ACTIVE);

        ProductVariant trainingSocks39 = ensureVariant(
                trainingSocks,
                "SEED-SOCK-WHT-39",
                "39-41",
                "White",
                129000,
                159000,
                9,
                VariantStatus.ACTIVE);
        ensureVariant(
                trainingSocks, "SEED-SOCK-BLK-42", "42-44", "Black", 129000, 159000, 14, VariantStatus.ACTIVE);

        ensureVariant(
                compressionTank,
                "SEED-TANK-BLU-M",
                "M",
                "Blue",
                299000,
                359000,
                8,
                VariantStatus.ACTIVE);
        ensureVariant(
                compressionTank,
                "SEED-TANK-GRY-L",
                "L",
                "Gray",
                299000,
                359000,
                4,
                VariantStatus.INACTIVE);

        ProductVariant windbreakerM = ensureVariant(
                windbreaker,
                "SEED-WIND-BLK-M",
                "M",
                "Black",
                690000,
                790000,
                15,
                VariantStatus.ACTIVE);
        ensureVariant(
                windbreaker, "SEED-WIND-BLK-L", "L", "Black", 690000, 790000, 11, VariantStatus.ACTIVE);

        // Seed products intentionally have no mock/stock image (was picsum.photos).
        // They render the neutral "no image" placeholder until a real image is added.

        ensureSeedOrder(
                "[SEED] confirmed-cod",
                customerTwo,
                footballBoots42,
                2,
                PaymentMethod.COD,
                List.of(OrderStatus.CONFIRMED),
                false);
        ensureSeedOrder(
                "[SEED] delivered-cod",
                customerThree,
                trainingSocks39,
                3,
                PaymentMethod.COD,
                List.of(OrderStatus.CONFIRMED, OrderStatus.PACKING, OrderStatus.SHIPPING, OrderStatus.DELIVERED),
                false);
        // Phase N5: a small, illustrative amount of review data (per the explicit
        // "khong nhoi seed qua lon" instruction) so a fresh local/demo environment
        // already has something to look at on the PDP.
        ensureApprovedReview(customerThree, trainingSocks);

        return new SeedSummary(
                countSeedUsers(),
                countSeedProducts(),
                countSeedVariants(),
                countSeedOrders());
    }

    /**
     * Reuses the already-DELIVERED "[SEED] delivered-cod" order from
     * ensureSeedOrder above - ReviewService.create requires a real delivered
     * purchase, same rule as the customer-facing API, so this seeds through
     * the actual service rather than inserting a row directly.
     */
    private void ensureApprovedReview(User customer, Product product) {
        if (productReviewRepository.findAllByProductIdAndStatus(
                        product.getId(), ReviewStatus.APPROVED, org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .anyMatch(r -> r.getUser().getId().equals(customer.getId()))) {
            return;
        }
        try {
            var review = reviewService.create(
                    customer.getId(), product.getId(), new CreateReviewRequest(5, "Rat tot!", "San pham dung nhu mo ta, giao nhanh. [SEED]"));
            reviewService.updateStatus(review.id(), new UpdateReviewStatusRequest(ReviewStatus.APPROVED));
        } catch (com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException ex) {
            log.warn("Skipping seed review for product {} and user {}: {}", product.getId(), customer.getId(), ex.getMessage());
        }
    }

    private User ensureUser(String email, String fullName, String phone, UserRole role) {
        User user = userRepository.findByEmail(email).orElseGet(User::new);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPhone(phone);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode(demoPassword()));
        return userRepository.save(user);
    }

    private Category ensureCategory(String slug, String name, String description) {
        Category category = categoryRepository.findBySlug(slug).orElseGet(Category::new);
        category.setSlug(slug);
        category.setName(name);
        category.setDescription(description);
        category.setStatus(CategoryStatus.ACTIVE);
        return categoryRepository.save(category);
    }

    private Brand ensureBrand(String slug, String name, String description) {
        Brand brand = brandRepository.findBySlug(slug).orElseGet(Brand::new);
        brand.setSlug(slug);
        brand.setName(name);
        brand.setDescription(description);
        brand.setStatus(BrandStatus.ACTIVE);
        return brandRepository.save(brand);
    }

    private Product ensureProduct(
            String slug,
            String name,
            Category category,
            Brand brand,
            String shortDescription,
            String description,
            Gender gender,
            String sportType,
            ProductStatus status,
            boolean featured) {
        Product product = productRepository.findBySlug(slug).orElseGet(Product::new);
        product.setSlug(slug);
        product.setName(name);
        product.setCategory(category);
        product.setBrand(brand);
        product.setShortDescription(shortDescription);
        product.setDescription(description);
        product.setGender(gender);
        product.setSportType(sportType);
        product.setStatus(status);
        product.setFeatured(featured);
        return productRepository.save(product);
    }

    private ProductVariant ensureVariant(
            Product product,
            String sku,
            String size,
            String color,
            int price,
            Integer compareAtPrice,
            int initialStock,
            VariantStatus status) {
        Optional<ProductVariant> existing = variantRepository.findBySku(sku);
        ProductVariant variant = existing.orElseGet(ProductVariant::new);
        variant.setProduct(product);
        variant.setSku(sku);
        variant.setSize(size);
        variant.setColor(color);
        variant.setPrice(BigDecimal.valueOf(price));
        variant.setCompareAtPrice(compareAtPrice != null ? BigDecimal.valueOf(compareAtPrice) : null);
        variant.setStatus(status);
        if (existing.isEmpty()) {
            variant.setStockQuantity(initialStock);
            variant.setReservedQuantity(0);
        }
        return variantRepository.save(variant);
    }

    private void ensureImage(Product product, String publicId, String imageUrl, boolean primary, int sortOrder) {
        ProductImage image = imageRepository.findByPublicId(publicId).orElseGet(ProductImage::new);
        image.setProduct(product);
        image.setPublicId(publicId);
        image.setImageUrl(imageUrl);
        image.setAltText(product.getName());
        image.setSortOrder(sortOrder);
        image.setPrimary(primary);
        imageRepository.save(image);
    }

    private void ensureSeedOrder(
            String note,
            User customer,
            ProductVariant variant,
            int quantity,
            PaymentMethod paymentMethod,
            List<OrderStatus> transitions,
            boolean cancelByCustomer) {
        if (orderRepository.existsByNote(note)) {
            return;
        }

        Address address = ensureAddress(customer);
        Cart cart = ensureUserCart(customer);
        cartItemRepository.deleteAll(cartItemRepository.findAllByCartId(cart.getId()));

        CartItem cartItem = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId())
                .orElseGet(CartItem::new);
        cartItem.setCart(cart);
        cartItem.setVariant(variant);
        cartItem.setQuantity(quantity);
        cartItemRepository.save(cartItem);

        OrderResponse createdOrder = orderService.createOrderFromCart(
                customer.getId(),
                new CreateOrderRequest(address.getId(), paymentMethod, note, null, null, null, null, null, null, null));
        UUID orderId = createdOrder.id();

        for (OrderStatus status : transitions) {
            User admin = userRepository.findByEmail(DEMO_ADMIN_EMAIL).orElseThrow();
            orderService.updateOrderStatus(orderId, new UpdateOrderStatusRequest(status, "[SEED] " + status), admin);
        }

        if (cancelByCustomer) {
            orderService.cancelOwnOrder(orderId, customer.getId(), "Seed cancelled order");
        }
    }

    private Address ensureAddress(User user) {
        return addressRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId()).orElseGet(() -> {
            Address address = new Address();
            address.setUser(user);
            address.setReceiverName(user.getFullName());
            address.setPhone(user.getPhone());
            address.setProvince("Ho Chi Minh");
            address.setDistrict("District 1");
            address.setWard("Ben Nghe");
            address.setAddressLine("Seed address for " + user.getFullName());
            address.setDefault(true);
            return addressRepository.save(address);
        });
    }

    private Cart ensureUserCart(User user) {
        return cartRepository.findByUserId(user.getId()).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setUser(user);
            return cartRepository.save(cart);
        });
    }

    private String demoPassword() {
        String configured = seedProperties.demoPassword();
        return configured != null && !configured.isBlank() ? configured : DEFAULT_DEMO_PASSWORD;
    }

    private long countSeedUsers() {
        return SEED_USER_EMAILS.stream()
                .filter(email -> userRepository.findByEmail(email).isPresent())
                .count();
    }

    private long countSeedProducts() {
        return SEED_PRODUCT_SLUGS.stream()
                .filter(slug -> productRepository.findBySlug(slug).isPresent())
                .count();
    }

    private long countSeedVariants() {
        return SEED_VARIANT_SKUS.stream()
                .filter(sku -> variantRepository.findBySku(sku).isPresent())
                .count();
    }

    private long countSeedOrders() {
        return SEED_ORDER_NOTES.stream()
                .filter(orderRepository::existsByNote)
                .count();
    }
}

