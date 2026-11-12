package dev.arthurgaspar.guildledger.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
class CatalogController {

    private final Catalog catalog;

    CatalogController(Catalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    Catalog.Data get() {
        return catalog.data();
    }
}
