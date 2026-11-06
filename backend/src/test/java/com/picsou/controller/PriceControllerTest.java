package com.picsou.controller;

import com.picsou.dto.PreciousMetalProductResponse;
import com.picsou.model.PreciousMetal;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.service.PriceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PriceControllerTest {

    private final PriceController controller =
        new PriceController(mock(PriceService.class), mock(PriceSnapshotRepository.class));

    @Test
    void preciousMetalsCatalogue_listsTheRegistryInOrder() {
        List<PreciousMetalProductResponse> catalogue = controller.getPreciousMetals();

        assertThat(catalogue).hasSize(PreciousMetal.values().length);
        assertThat(catalogue.get(0)).isEqualTo(
            new PreciousMetalProductResponse("GOLD:GRAM", "Gold (fine gram)", PreciousMetal.Unit.GRAM));
        assertThat(catalogue.get(1)).isEqualTo(
            new PreciousMetalProductResponse("GOLD:NAPOLEON-20F", "Napoléon 20 F", PreciousMetal.Unit.COIN));
        assertThat(catalogue.stream().map(PreciousMetalProductResponse::ticker).toList())
            .isEqualTo(java.util.Arrays.stream(PreciousMetal.values()).map(PreciousMetal::ticker).toList());
    }
}
