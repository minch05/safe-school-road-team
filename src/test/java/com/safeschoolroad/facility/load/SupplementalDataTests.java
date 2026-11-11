package com.safeschoolroad.facility.load;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.FacilityRepository;
import com.safeschoolroad.guide.GuidePoint;
import com.safeschoolroad.guide.TemplateGuide;
import com.safeschoolroad.route.analysis.PointReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplementalDataTests {
    @TempDir Path dir;

    @Test void 보안등_서울만_읽고_가게명이_섞인_위치명은_저장하지_않는다() throws Exception {
        Files.writeString(dir.resolve(PublicDataFiles.SECURITY_LIGHT_FILE), """
                {"records":[
                  {"보안등위치명":"비공개상점 앞","소재지도로명주소":"서울특별시 중랑구 면목로 434","위도":"37.59","경도":"127.08","데이터기준일자":"2026-10-01"},
                  {"보안등위치명":"서울 좌표 오류","관리기관명":"서울특별시","위도":"0","경도":"0"},
                  {"보안등위치명":"타지역","소재지도로명주소":"부산광역시","위도":"35.1","경도":"129.0"}
                ]}
                """);
        var rows = new PublicDataFiles(dir).readSecurityLights();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).kind()).isEqualTo(FacilityKind.SECURITY_LIGHT);
        assertThat(rows.get(0).name()).isEqualTo("면목로");
        assertThat(rows.get(0).sourceId()).hasSize(32);
        assertThat(FacilityKind.SECURITY_LIGHT.isKeyPointKind()).isFalse();
    }

    @Test void 지킴이집_UTF8과_CP949_파일을_읽는다() throws Exception {
        String csv = "시설번호,시설명,소재지도로명주소,위도,경도,데이터기준일자\n"
                + "a,상점 이름,서울특별시 중랑구 면목로 434,37.59,127.08,2026-10-01\n";
        for (Charset charset : new Charset[]{java.nio.charset.StandardCharsets.UTF_8, Charset.forName("MS949")}) {
            Files.writeString(dir.resolve(PublicDataFiles.SAFETY_HOUSE_FILE), csv, charset);
            var rows = new PublicDataFiles(dir).readChildSafetyHouses();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).kind()).isEqualTo(FacilityKind.CHILD_SAFETY_HOUSE);
            assertThat(rows.get(0).name()).isEqualTo("면목로");
            assertThat(TemplateGuide.sentence(new GuidePoint(1, rows.get(0).kind(), rows.get(0).name(), null, PointReason.GAP, false)))
                    .contains("아동안전지킴이집").doesNotContain("상점 이름");
        }
    }

    @Test void 잘린_JSON이면_DB_삭제를_시작하지_않는다() throws Exception {
        Files.writeString(dir.resolve(PublicDataFiles.SECURITY_LIGHT_FILE), "{\"records\":[{\"위도\":\"37.59\"");
        var repository = mock(FacilityRepository.class);
        var transaction = mock(TransactionTemplate.class);
        var importer = new FacilityImporter(repository, transaction, mock(ConfigurableApplicationContext.class), mock(PublicDataBackup.class),mock(SignalReestimator.class));
        assertThatThrownBy(() -> importer.importSupplemental("서울", dir)).isInstanceOf(java.io.IOException.class);
        verifyNoInteractions(repository, transaction);
    }
    @Test void 백업실패하면_추가시설을_삭제하지않는다() throws Exception {
        Files.writeString(dir.resolve(PublicDataFiles.SECURITY_LIGHT_FILE),"{\"records\":[{\"소재지도로명주소\":\"서울특별시 중랑구 면목로 434\",\"위도\":\"37.59\",\"경도\":\"127.08\"}]}");
        var repo=mock(FacilityRepository.class);var tx=mock(TransactionTemplate.class);var backup=mock(PublicDataBackup.class);
        doAnswer(inv->{java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action=inv.getArgument(0);action.accept(mock(org.springframework.transaction.TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
        when(backup.write(any())).thenThrow(new IllegalStateException("백업 실패"));
        var importer=new FacilityImporter(repo,tx,mock(ConfigurableApplicationContext.class),backup,mock(SignalReestimator.class));
        assertThatThrownBy(()->importer.importSupplemental("중랑구",dir)).hasMessage("백업 실패");
        verify(repo).snapshotSourceRegion("SECURITY_LIGHT_STD","중랑구");verify(repo,never()).deleteSourceRegion(anyString(),anyString());verify(repo,never()).insertAll(any());
    }
}
