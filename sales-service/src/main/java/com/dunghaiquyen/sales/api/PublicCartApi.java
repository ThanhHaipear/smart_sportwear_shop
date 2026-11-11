package com.dunghaiquyen.sales.api;

import com.dunghaiquyen.sales.domain.CartApplicationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
public class PublicCartApi {
    private final CartApplicationService carts;
    public PublicCartApi(CartApplicationService carts){this.carts=carts;}
    public record AddItem(@NotNull UUID variantId,@NotNull @Positive Integer quantity){}
    public record UpdateItem(@NotNull @Positive Integer quantity){}

    @GetMapping ApiResponse<Map<String,Object>> cart(@RequestHeader(value="X-User-Id",required=false)UUID user,HttpServletRequest request,HttpServletResponse response){
        return ApiResponse.ok(carts.getOrCreate(user,session(user,request,response)));}
    @PostMapping("/items") ResponseEntity<ApiResponse<Map<String,Object>>> add(@RequestHeader(value="X-User-Id",required=false)UUID user,HttpServletRequest request,HttpServletResponse response,@Valid @RequestBody AddItem body){
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(carts.add(user,session(user,request,response),body.variantId(),body.quantity())));}
    @PatchMapping("/items/{id}") ApiResponse<Map<String,Object>> update(@RequestHeader(value="X-User-Id",required=false)UUID user,HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id,@Valid @RequestBody UpdateItem body){
        return ApiResponse.ok(carts.update(user,session(user,request,response),id,body.quantity()));}
    @DeleteMapping("/items/{id}") ApiResponse<Map<String,Object>> remove(@RequestHeader(value="X-User-Id",required=false)UUID user,HttpServletRequest request,HttpServletResponse response,@PathVariable UUID id){
        return ApiResponse.ok(carts.remove(user,session(user,request,response),id));}

    private String session(UUID user,HttpServletRequest request,HttpServletResponse response){
        if(user!=null)return null;if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if("session_id".equals(cookie.getName())&&!cookie.getValue().isBlank())return cookie.getValue();
        String value=UUID.randomUUID().toString();response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("session_id",value).httpOnly(true).secure(false).sameSite("Lax").path("/").maxAge(2592000).build().toString());return value;
    }
}
