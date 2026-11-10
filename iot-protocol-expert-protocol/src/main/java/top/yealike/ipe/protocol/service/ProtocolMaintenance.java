package top.yealike.ipe.protocol.service;
import top.yealike.ipe.protocol.netty.SessionRegistry;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.SettingFrames;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
@Component
public class ProtocolMaintenance {
    private final SessionRegistry sessions;
    public ProtocolMaintenance(SessionRegistry sessions){this.sessions=sessions;}
    @Scheduled(cron="0 0 3 * * *",zone="Asia/Shanghai")
    public void dailyTimeSync(){for(String pile:sessions.onlinePiles()){
        try{sessions.send(pile,FrameType.TIME_SYNC,SettingFrames.encodeTimeSync(new SettingFrames.TimeSync(pile,LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))));}catch(DeviceService.DeviceOfflineException ignored){}
    }}
}
