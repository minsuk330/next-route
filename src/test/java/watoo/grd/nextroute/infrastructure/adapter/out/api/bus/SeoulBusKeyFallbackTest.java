package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class SeoulBusKeyFallbackTest {

	private static final URI POS = URI.create("http://ws.bus.go.kr/api/rest/buspos/getBusPosByRtid?serviceKey=new%2Bkey&busRouteId=1");
	private static final URI ARR = URI.create("http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRoute?serviceKey=new%2Bkey&stId=1");

	@Test
	void TC_대체키가_없으면_아무것도_바꾸지_않는다() {
		SeoulBusKeyFallback keys = new SeoulBusKeyFallback("new%2Bkey", "");

		assertThat(keys.preferred(POS)).isEqualTo(POS);
		assertThat(keys.alternate(POS)).isEmpty();
	}

	@Test
	void TC_대체키가_주키와_같으면_꺼진_것으로_본다() {
		assertThat(new SeoulBusKeyFallback("new%2Bkey", "new%2Bkey").alternate(POS)).isEmpty();
	}

	@Test
	void TC_성공한_키를_기능별로_기억하고_다른_기능은_주키_그대로다() {
		SeoulBusKeyFallback keys = new SeoulBusKeyFallback("new%2Bkey", "old%3Dkey");

		URI other = keys.alternate(POS).orElseThrow();
		assertThat(other.toString()).isEqualTo(
				"http://ws.bus.go.kr/api/rest/buspos/getBusPosByRtid?serviceKey=old%3Dkey&busRouteId=1");
		keys.markWorking(other);

		assertThat(keys.preferred(POS)).isEqualTo(other);
		assertThat(keys.preferred(ARR)).isEqualTo(ARR);
	}

	@Test
	void TC_기억한_대체키가_401이면_주키로_돌아온다() {
		SeoulBusKeyFallback keys = new SeoulBusKeyFallback("new%2Bkey", "old%3Dkey");
		keys.markWorking(keys.alternate(POS).orElseThrow());
		URI usingFallback = keys.preferred(POS);

		URI back = keys.alternate(usingFallback).orElseThrow();
		assertThat(back).isEqualTo(POS);
		keys.markWorking(back);

		assertThat(keys.preferred(POS)).isEqualTo(POS);
	}

	@Test
	void TC_키가_다른_키의_앞부분이어도_정확히_일치할_때만_바꾼다() {
		SeoulBusKeyFallback keys = new SeoulBusKeyFallback("abc", "abcdef");
		URI withLong = URI.create("http://h/p?serviceKey=abcdef&x=1");

		assertThat(keys.alternate(withLong).orElseThrow().toString()).isEqualTo("http://h/p?serviceKey=abc&x=1");
	}
}
