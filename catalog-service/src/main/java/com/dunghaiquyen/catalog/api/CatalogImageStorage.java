package com.dunghaiquyen.catalog.api;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CatalogImageStorage {
    private static final Set<String> TYPES=Set.of("image/jpeg","image/png","image/webp");
    private final Path root;
    public CatalogImageStorage(@Value("${app.image-storage-directory:./data/catalog-images}")String root){this.root=Path.of(root).toAbsolutePath().normalize();}
    public StoredImage store(MultipartFile file){
        if(file==null||file.isEmpty())throw new IllegalArgumentException("Image file is required");
        if(file.getSize()>10*1024*1024)throw new IllegalArgumentException("Image must not exceed 10 MB");
        String contentType=file.getContentType();if(contentType==null||!TYPES.contains(contentType.toLowerCase()))throw new IllegalArgumentException("Only JPEG, PNG and WebP images are supported");
        String extension=switch(contentType.toLowerCase()){case "image/png"->".png";case "image/webp"->".webp";default->".jpg";};String name=UUID.randomUUID()+extension;Path target=root.resolve(name).normalize();if(!target.startsWith(root))throw new SecurityException("Invalid image path");
        try{Files.createDirectories(root);file.transferTo(target);BufferedImage image=ImageIO.read(target.toFile());return new StoredImage(name,"/api/v1/catalog-images/"+name,image==null?null:image.getWidth(),image==null?null:image.getHeight());}catch(IOException error){throw new IllegalStateException("Cannot store product image",error);}
    }
    public byte[] read(String name){if(!name.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp)"))throw new IllegalArgumentException("Invalid image name");Path file=root.resolve(name).normalize();if(!file.startsWith(root)||!Files.isRegularFile(file))throw new NotFoundException("Image not found");try{return Files.readAllBytes(file);}catch(IOException error){throw new IllegalStateException("Cannot read product image",error);}}
    public record StoredImage(String publicId,String url,Integer width,Integer height){}
}
