package com.safeschoolroad.push;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 최대 5분 보관하는 기기별 전송 대기. 좌표·구독 인증키는 복사하지 않는다. */
@Entity @Table(name="push_pending",indexes=@Index(name="idx_push_pending_due",columnList="nextAttemptAt"))
public class PushPending {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private long recipientId;
    private Long guardianLinkId;
    private Long tripId;
    private String tripEvent;
    private long subscriptionId;
    @Column(nullable=false,length=64) private String subscriptionStamp;
    @Column(nullable=false,length=100) private String title;
    @Column(nullable=false,length=500) private String body;
    @Column(nullable=false,length=200) private String url;
    private LocalDateTime expiresAt;
    private LocalDateTime nextAttemptAt;
    private int attempts;
    protected PushPending() {}
    public PushPending(PushSubscription s,String title,String body,String url,LocalDateTime now) {
        recipientId=s.getMember().getId();subscriptionId=s.getId();subscriptionStamp=stamp(s);
        this.title=title;this.body=body;this.url=url;expiresAt=now.plusMinutes(5);nextAttemptAt=now;
    }
    public PushPending(PushSubscription s,String title,String body,String url,LocalDateTime now,Long linkId) {
        this(s,title,body,url,now);this.guardianLinkId=linkId;
    }
    public PushPending(PushSubscription s,String title,String body,String url,LocalDateTime now,Long linkId,Long tripId,String event) {
        this(s,title,body,url,now,linkId);this.tripId=tripId;this.tripEvent=event;
    }
    public Long getTripId(){return tripId;}
    public boolean currentFor(com.safeschoolroad.trip.Trip trip) {
        if(trip==null||tripEvent==null)return false;
        return switch(tripEvent) {
            case "START" -> trip.getArrivedAt()==null;
            case "DELAY" -> trip.getArrivedAt()==null && trip.getDelayAlertSentAt()!=null;
            case "ARRIVAL" -> trip.getArrivedAt()!=null;
            default -> false;
        };
    }
    public Long getGuardianLinkId(){return guardianLinkId;}
    static String stamp(PushSubscription s) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((s.getEndpoint()+"\n"+s.getP256dh()+"\n"+s.getAuth()+"\n"+s.getRegistrationGeneration()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public boolean matches(PushSubscription s){return recipientId==s.getMember().getId() && subscriptionStamp.equals(stamp(s));}
    public boolean expired(LocalDateTime now){return !expiresAt.isAfter(now)||attempts>=5;}
    public boolean due(LocalDateTime now){return !nextAttemptAt.isAfter(now);}
    public void retry(LocalDateTime now){attempts++;nextAttemptAt=now.plusSeconds(30L*attempts);}
    public Long getId(){return id;}
    public long getSubscriptionId(){return subscriptionId;}
    public String getTitle(){return title;}
    public String getBody(){return body;}
    public String getUrl(){return url;}
}
