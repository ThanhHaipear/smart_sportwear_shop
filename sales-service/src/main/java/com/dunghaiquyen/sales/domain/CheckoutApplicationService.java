package com.dunghaiquyen.sales.domain;

import com.dunghaiquyen.sales.catalog.CatalogClient;
import com.dunghaiquyen.sales.domain.SalesModels.OrderLineRequest;
import com.dunghaiquyen.sales.domain.SalesModels.VariantSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CheckoutApplicationService {
    private final CartApplicationService carts; private final CatalogClient catalog;
    public CheckoutApplicationService(CartApplicationService carts,CatalogClient catalog){this.carts=carts;this.catalog=catalog;}
    public List<OrderLineRequest> lines(UUID user,List<UUID> cartItemIds,UUID buyNowVariantId,Integer buyNowQuantity){
        if(buyNowVariantId!=null)return List.of(new OrderLineRequest(buyNowVariantId,buyNowQuantity==null?1:buyNowQuantity));
        return carts.orderLines(user,cartItemIds);
    }
    public Map<String,Object> preview(UUID user,List<UUID> cartItemIds,UUID buyNowVariantId,Integer buyNowQuantity){
        List<Map<String,Object>> items=new ArrayList<>();BigDecimal subtotal=BigDecimal.ZERO;boolean valid=true;
        for(OrderLineRequest line:lines(user,cartItemIds,buyNowVariantId,buyNowQuantity)){
            VariantSnapshot snapshot=catalog.snapshot(line.variantId());boolean itemValid=snapshot.active()&&snapshot.availableQuantity()>=line.quantity();
            BigDecimal total=snapshot.price().multiply(BigDecimal.valueOf(line.quantity()));subtotal=subtotal.add(total);valid&=itemValid;
            Map<String,Object> item=new LinkedHashMap<>();item.put("variantId",line.variantId());item.put("productId",snapshot.productId());
            item.put("productName",snapshot.productName());item.put("sku",snapshot.sku());item.put("quantity",line.quantity());
            item.put("unitPrice",snapshot.price());item.put("lineTotal",total);item.put("valid",itemValid);
            item.put("errorMessage",itemValid?null:"Sản phẩm không còn đủ tồn kho");items.add(item);
        }
        Map<String,Object> response=new LinkedHashMap<>();response.put("items",items);response.put("subtotal",subtotal);
        response.put("discountAmount",BigDecimal.ZERO);response.put("shippingFee",BigDecimal.ZERO);response.put("totalAmount",subtotal);
        response.put("canCheckout",valid&&!items.isEmpty());return response;
    }
}
