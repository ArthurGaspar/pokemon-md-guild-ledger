package dev.arthurgaspar.guildledger.mission;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Embeddable
public record ItemStack(@NotBlank @Column(name = "item_code") String itemCode, @Min(1) int quantity) {}
