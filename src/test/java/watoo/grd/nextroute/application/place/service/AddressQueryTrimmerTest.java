package watoo.grd.nextroute.application.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AddressQueryTrimmerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "경기 고양시 일산동구 중앙로 1305-30 101동 1203호 | 경기 고양시 일산동구 중앙로 1305-30",
            "서울 송파구 올림픽로 300 1001호                 | 서울 송파구 올림픽로 300",
            "서울 송파구 올림픽로 300, 1001호                | 서울 송파구 올림픽로 300",
            "서울 강남구 테헤란로 152 지하1층                | 서울 강남구 테헤란로 152",
            "서울 강남구 테헤란로 152 B1층                   | 서울 강남구 테헤란로 152",
            "서울 강남구 역삼동 737 3층                      | 서울 강남구 역삼동 737",
            "서울 강남구 테헤란로 152 (역삼동)               | 서울 강남구 테헤란로 152",
            "중앙로 1305-30 101동1203호                      | 중앙로 1305-30",
    })
    void dropsTrailingUnits(String query, String expected) {
        assertThat(AddressQueryTrimmer.trim(query)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "서울 관악구 신림1동",
            "서울 양천구 목3동",
            "서울 관악구 봉천동 1570-1",
            "서울 송파구 올림픽로 300",
            "스타벅스 강남역점",
    })
    void keepsAdministrativeNamesAndPlainAddresses(String query) {
        assertThat(AddressQueryTrimmer.trim(query)).isEqualTo(query);
    }

    @ParameterizedTest
    @CsvSource({"서울 송파구 올림픽로 300,true", "스타벅스 강남역점,false", "강남역,false"})
    void mayBeAddressNeedsABuildingNumber(String query, boolean expected) {
        assertThat(AddressQueryTrimmer.mayBeAddress(query)).isEqualTo(expected);
    }
}
