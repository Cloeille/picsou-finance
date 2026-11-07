package com.picsou.port;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the official name of each regional bank: it is what the sync stamps on {@code account.provider}
 * and the name the Enable Banking catalog is searched under, so an accidental edit would silently
 * give the accounts another label and lose their logo.
 */
class BanquePopulaireRegionTest {

    @Test
    void eachRegionCarriesItsOfficialName() {
        Map<BanquePopulaireRegion, String> expected = new LinkedHashMap<>();
        expected.put(BanquePopulaireRegion.ALSACE_LORRAINE_CHAMPAGNE, "Banque Populaire Alsace Lorraine Champagne");
        expected.put(BanquePopulaireRegion.AQUITAINE_CENTRE_ATLANTIQUE, "Banque Populaire Aquitaine Centre Atlantique");
        expected.put(BanquePopulaireRegion.AUVERGNE_RHONE_ALPES, "Banque Populaire Auvergne Rhône Alpes");
        expected.put(BanquePopulaireRegion.BOURGOGNE_FRANCHE_COMTE, "Banque Populaire Bourgogne Franche-Comté");
        expected.put(BanquePopulaireRegion.BRED, "BRED Banque Populaire");
        expected.put(BanquePopulaireRegion.GRAND_OUEST, "Banque Populaire Grand Ouest");
        expected.put(BanquePopulaireRegion.MEDITERRANEE, "Banque Populaire Méditerranée");
        expected.put(BanquePopulaireRegion.NORD, "Banque Populaire du Nord");
        expected.put(BanquePopulaireRegion.OCCITANE, "Banque Populaire Occitane");
        expected.put(BanquePopulaireRegion.RIVES_DE_PARIS, "Banque Populaire Rives de Paris");
        expected.put(BanquePopulaireRegion.SUD, "Banque Populaire du Sud");
        expected.put(BanquePopulaireRegion.VAL_DE_FRANCE, "Banque Populaire Val de France");

        assertThat(BanquePopulaireRegion.values()).containsExactlyElementsOf(expected.keySet());
        for (BanquePopulaireRegion region : BanquePopulaireRegion.values()) {
            assertThat(region.institutionName()).isEqualTo(expected.get(region));
        }
    }

    @Test
    void theNamesFitTheProviderColumnAndAreDistinct() {
        assertThat(Arrays.stream(BanquePopulaireRegion.values()).map(BanquePopulaireRegion::institutionName))
            .doesNotHaveDuplicates()
            .allSatisfy(name -> assertThat(name.length()).isLessThanOrEqualTo(100));
    }
}
