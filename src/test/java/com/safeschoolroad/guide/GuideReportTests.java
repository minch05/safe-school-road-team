package com.safeschoolroad.guide;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class GuideReportTests {
 @Test void 호출_실패를_AI_생성_성공으로_표시하지_않는다(){
  String r=GuideReport.report(List.of(new GuideLog("-","LOWER",5,1,"TEMPLATE",List.of("1회: AI 호출 실패"))));
  assertThat(r).contains("AI 호출 시도: 1건","최종 통과(3번 안): 0건","정해진 문장 전환: 1건","호출 실패=1").doesNotContain("저장된 안내문의 오류: 0건");
 }
 @Test void 새_검사_원인을_구분하고_재시도_성공을_집계한다(){
  String r=GuideReport.report(List.of(new GuideLog("test","LOWER",5,2,"AI",List.of("1회: 이름 없는 횡단보도예요.","1회: 길을 꺾는 곳이에요."))));
  assertThat(r).contains("횡단보도 이름 혼용=1","꺾는 안내=1","최종 통과(3번 안): 1건","정해진 문장 전환: 0건");
 }
 @Test void 기록이_없어도_현재_정확도를_단정하지_않는다(){
  assertThat(GuideReport.report(List.of())).contains("최근 0건","현장 정확도 검증은 아니에요").doesNotContain("NaN","오류: 0건");
 }
}
