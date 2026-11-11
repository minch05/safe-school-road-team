package com.safeschoolroad.route;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.route.analysis.RouteAnalysis;
import com.safeschoolroad.route.analysis.CrimeExposure;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class RouteChoiceTests {
    RouteCandidateService.Candidate candidate(int time,int crossings,int unknown,List<String> accidents,CrimeExposure crime){
        return new RouteCandidateService.Candidate(List.of(0),new RouteAnalysis(new TmapRoute(List.of(),List.of(),time,time),List.of(),crossings,crossings-unknown,unknown,0,List.of(),accidents,0,0),crime);
    }
    CrimeExposure known(long upper,long missing,int coverage){return new CrimeExposure(true,200,missing,upper,coverage,8,List.of(),"");}
    @Test void 짧은_우회에서_등록된_사고_영역과_신호_미확인을_먼저_비교한다(){
        var unknown=CrimeExposure.unknown("");
        assertThat(RouteChoice.suggest(List.of(candidate(180,1,0,List.of("사고"),unknown),candidate(230,1,1,List.of(),unknown),candidate(250,2,0,List.of(),unknown))).index()).isEqualTo(2);
    }
    @Test void 이분을_넘는_우회는_자동_제안에서_제외하지만_후보를_삭제하지_않는다(){
        var list=List.of(candidate(180,1,1,List.of("사고"),CrimeExposure.unknown("")),candidate(310,0,0,List.of(),CrimeExposure.unknown("")));
        assertThat(RouteChoice.suggest(list).index()).isZero();assertThat(list).hasSize(2);
    }
    @Test void 범죄_미확인을_안전으로_해석하지_않고_명확한_차이만_반영한다(){
        assertThat(RouteChoice.suggest(List.of(candidate(180,1,0,List.of(),known(100,0,100)),candidate(190,1,0,List.of(),known(20,5,98)))).index()).isEqualTo(1);
        assertThat(RouteChoice.suggest(List.of(candidate(180,1,0,List.of(),known(100,0,100)),candidate(190,1,0,List.of(),CrimeExposure.unknown("")))).index()).isZero();
        assertThat(RouteChoice.crimeClearlyShorter(candidate(180,1,0,List.of(),known(20,80,90)),candidate(190,1,0,List.of(),known(80,0,100)))).isFalse();
    }
    @Test void 한_후보와_빈_후보_목록을_처리한다(){
        assertThat(RouteChoice.suggest(List.of()).index()).isEqualTo(-1);
        assertThat(RouteChoice.suggest(List.of(candidate(180,0,0,List.of(),CrimeExposure.unknown("")))).index()).isZero();
        var single=RouteChoice.suggest(List.of(candidate(180,0,0,List.of(),CrimeExposure.unknown(""))));
        assertThat(single.rule()).contains("순위 비교는 하지 않았어요", "출입구").doesNotContain("2분", "다른 후보도 직접 선택");
    }
    @Test void 다른_후보가_미확인이면_범죄_항목으로_순위를_정하지_않는다(){
        var list=List.of(candidate(180,1,0,List.of(),known(100,0,100)),candidate(190,1,0,List.of(),known(20,0,100)),candidate(200,1,0,List.of(),CrimeExposure.unknown("")));
        assertThat(RouteChoice.suggest(list).index()).isZero();
    }

    @Test void 제안_이유에_실제_후보_번호와_시간_차이를_설명한다() {
        var result = RouteChoice.suggest(List.of(candidate(180,1,1,List.of("사고"),CrimeExposure.unknown("")),
                candidate(230,1,0,List.of(),CrimeExposure.unknown(""))));
        assertThat(result.summary()).contains("1번", "50초", "사고 영역 겹침이 1곳 적어요");
        assertThat(result.reasons()).anyMatch(r -> r.contains("1번 길과 비교") && r.contains("상대 1곳"));
    }
    @Test void 차이가_없는_항목으로_더_안전하다고_말하지_않는다() {
        var result = RouteChoice.suggest(List.of(candidate(180,1,0,List.of(),CrimeExposure.unknown("")),
                candidate(190,1,0,List.of(),CrimeExposure.unknown(""))));
        assertThat(result.summary()).contains("같아요", "시간과 거리").doesNotContain("더 안전");
    }
    @Test void 동일_비교값은_시간기준으로_표시하고_단일후보는_비교없음으로_표시한다(){
        var a=candidate(180,1,1,List.of(),CrimeExposure.unknown(""));
        var b=candidate(190,1,1,List.of(),CrimeExposure.unknown(""));
        assertThat(RouteChoice.suggest(List.of(a,b)).basis()).isEqualTo("TIME_DISTANCE");
        assertThat(RouteChoice.suggest(List.of(a)).basis()).isEqualTo("SINGLE");
    }
    @Test void 범죄자료로_선택했다면_후순위_건넘수가_아닌_범죄차이를_설명한다(){
        var a=candidate(180,1,0,List.of(),known(100,0,100));
        var b=candidate(190,2,0,List.of(),known(20,0,100));
        var result=RouteChoice.suggest(List.of(a,b));
        assertThat(result.index()).isEqualTo(1);assertThat(result.summary()).contains("범죄");
    }
    @Test void 세후보_요약은_먼저나온_신호차이보다_사고차이를_먼저_설명한다(){
        var unknown=CrimeExposure.unknown("");
        var result=RouteChoice.suggest(List.of(candidate(180,2,1,List.of(),unknown),
                candidate(190,2,0,List.of("사고"),unknown),candidate(200,2,0,List.of(),unknown)));
        assertThat(result.index()).isEqualTo(2);
        assertThat(result.summary()).contains("2번 길보다", "사고 영역 겹침이 1곳 적어요");
    }
    @Test void 우회제외_후보의_차이를_제안이유로_과장하지_않는다(){
        var unknown=CrimeExposure.unknown("");
        var result=RouteChoice.suggest(List.of(candidate(180,2,1,List.of(),unknown),
                candidate(400,1,0,List.of("사고"),unknown),candidate(200,2,0,List.of(),unknown)));
        assertThat(result.summary()).contains("1번 길보다", "신호").doesNotContain("사고 영역 겹침이");
    }
}
