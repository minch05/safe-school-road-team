package com.safeschoolroad.ops;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** 배포 상태 확인용. 키, DB 주소, 사용자/시설 행과 예외 원문을 반환하지 않는다. */
@RestController
public class ReadinessController {
 private final JdbcTemplate jdbc;
 private long checkedAt;private boolean up;
 public ReadinessController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping("/health/ready") public synchronized ResponseEntity<Map<String,String>> ready(){
  long now=System.currentTimeMillis();
  if(now-checkedAt<5000)return response(up);
  checkedAt=now;up=false;
  try{Integer n=jdbc.queryForObject("SELECT 1",Integer.class);up=Integer.valueOf(1).equals(n);}
  catch(Exception ignored){}
  return response(up);
 }
 private ResponseEntity<Map<String,String>> response(boolean ok){
  return ResponseEntity.status(ok?200:503).header("Cache-Control","no-store").body(Map.of("status",ok?"UP":"DOWN"));
 }
}
