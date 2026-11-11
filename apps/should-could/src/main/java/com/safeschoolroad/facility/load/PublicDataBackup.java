package com.safeschoolroad.facility.load;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.util.*;

/** 공개 시설 자료만 받는다. 백업 실패를 호출자의 교체 트랜잭션에 전달한다. */
@Component
public class PublicDataBackup {
    @Value("${PUBLIC_DATA_BACKUP_DIR:무제 폴더/품질검토/db-backups}")
    private String directory;
    public Path write(Object snapshot) {
        try {
            Path file=Path.of(directory,"public-data-"+UUID.randomUUID()+".json");
            Files.createDirectories(file.getParent());
            byte[] bytes=new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
            Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
            Files.writeString(Path.of(file+".sha256"),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),StandardOpenOption.CREATE_NEW);
            return file;
        } catch(Exception e){throw new IllegalStateException("공공자료 백업 실패. 기존 자료를 변경하지 않습니다.",e);}
    }
}
