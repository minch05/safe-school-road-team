package com.safeschoolroad.facility.load;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// 공공데이터 CSV 읽기. 따옴표로 묶인 칸("가, 나")과 칸 안의 줄바꿈을 처리한다.
// 첫 줄을 제목으로 보고, 한 줄마다 {제목: 값} 으로 넘긴다.
final class SimpleCsv {

    private SimpleCsv() {
    }

    static void forEachRow(Reader in, Consumer<Map<String, String>> handler) throws IOException {
        BufferedReader reader = new BufferedReader(in);
        List<String> header = readRecord(reader);
        if (header == null) {
            return;
        }
        if (!header.isEmpty()) {
            header.set(0, header.get(0).replace("﻿", "")); // 파일 맨 앞 BOM 표시 제거
        }
        List<String> record;
        while ((record = readRecord(reader)) != null) {
            if (record.size() == 1 && record.get(0).isEmpty()) {
                continue; // 빈 줄
            }
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i).strip(), i < record.size() ? record.get(i) : "");
            }
            handler.accept(row);
        }
    }

    private static List<String> readRecord(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        while (true) {
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            cell.append('"'); // "" 는 따옴표 한 개
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(ch);
                    }
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == ',') {
                    fields.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(ch);
                }
            }
            if (!quoted) {
                break;
            }
            // 따옴표 안에서 줄이 바뀌었다 → 다음 줄을 이어서 읽는다
            String next = reader.readLine();
            if (next == null) {
                throw new IOException("CSV가 따옴표 안에서 잘렸습니다. 기존 데이터는 유지합니다.");
            }
            cell.append('\n');
            line = next;
        }
        fields.add(cell.toString());
        return fields;
    }
}
