package com.safeschoolroad.facility.load;

import com.safeschoolroad.facility.RiskAreaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskImportAtomicityTests {
    @Test void 일부_구의_다운로드가_실패하면_어느_종류도_교체하지_않는다() {
        var repo=mock(RiskAreaRepository.class);var tx=mock(TransactionTemplate.class);
        var importer=new RiskAreaImporter(repo,tx,mock(ConfigurableApplicationContext.class),mock(PublicDataBackup.class)) {
            int fetched;
            @Override List<Object[]> fetch(String path,String code,String name) {
                if(++fetched==26)throw new IllegalStateException("두 번째 자료 수집 실패");
                return java.util.Collections.singletonList(new Object[]{"시설","{}",name,code});
            }
        };
        ReflectionTestUtils.setField(importer,"serviceKey","test");
        assertThatThrownBy(importer::importAll).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repo,tx);
    }
    @Test void 전체_빈_응답은_기존_사고자료를_지우지_않는다() {
        var repo=mock(RiskAreaRepository.class);var tx=mock(TransactionTemplate.class);
        var importer=new RiskAreaImporter(repo,tx,mock(ConfigurableApplicationContext.class),mock(PublicDataBackup.class)) {
            @Override List<Object[]> fetch(String path,String code,String name) {return List.of();}
        };
        ReflectionTestUtils.setField(importer,"serviceKey","test");
        assertThatThrownBy(importer::importAll).isInstanceOf(IllegalStateException.class).hasMessageContaining("0개");
        verifyNoInteractions(repo,tx);
    }
    @Test void 백업실패하면_사고자료를_삭제하지않는다() {
        var repo=mock(RiskAreaRepository.class);var tx=mock(TransactionTemplate.class);var backup=mock(PublicDataBackup.class);
        doAnswer(inv->{java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action=inv.getArgument(0);action.accept(mock(org.springframework.transaction.TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
        when(backup.write(any())).thenThrow(new IllegalStateException("백업 실패"));
        var importer=new RiskAreaImporter(repo,tx,mock(ConfigurableApplicationContext.class),backup) {
            @Override List<Object[]> fetch(String path,String code,String name){return java.util.Collections.singletonList(new Object[]{"합성","{}",name,code});}
        };
        ReflectionTestUtils.setField(importer,"serviceKey","test");
        assertThatThrownBy(importer::importAll).hasMessage("백업 실패");verify(repo,never()).deleteKind(anyString());
        verify(repo,never()).insert(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
}
