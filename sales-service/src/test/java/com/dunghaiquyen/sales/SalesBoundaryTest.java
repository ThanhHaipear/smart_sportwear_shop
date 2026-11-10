package com.dunghaiquyen.sales;
import static org.assertj.core.api.Assertions.*;import java.nio.file.*;import org.junit.jupiter.api.Test;
class SalesBoundaryTest{
 @Test void sourceDoesNotImportLegacyOrSeparatedServiceEntities()throws Exception{Path root=Path.of("src/main/java");try(var files=Files.walk(root)){String all=files.filter(p->p.toString().endsWith(".java")).map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}).reduce("",String::concat);assertThat(all).doesNotContain("com.dunghaiquyen.ecommerce").doesNotContain("modules.product.entity").doesNotContain("modules.user.entity");}}
 @Test void migrationHasNoCrossServiceForeignKeys()throws Exception{String sql=Files.readString(Path.of("src/main/resources/db/migration/V1__create_sales_schema.sql"));assertThat(sql).doesNotContain("references public.").contains("revoke all on schema public from sales_app").contains("user_ref").contains("variant_ref");}
}
