package top.yealike.ipe.protocol.service;

import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.domain.GunStateEntity;
import top.yealike.ipe.protocol.repository.GunStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 每枪实时状态。 */
@Service
public class GunStateService {

    private final GunStateRepository repository;

    public GunStateService(GunStateRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public GunStateEntity update(RealtimeFrames.Realtime r) {
        GunStateEntity state = repository.findByPileCodeAndGunNo(r.pileCode(), r.gunNo())
                .orElseGet(() -> {
                    GunStateEntity created = new GunStateEntity();
                    created.setPileCode(r.pileCode());
                    created.setGunNo(r.gunNo());
                    return created;
                });
        state.setState(r.state());
        state.setGunHomed(r.gunHomed());
        state.setPlugged(r.plugged());
        state.setVoltage(r.outputVoltage());
        state.setCurrent(r.outputCurrent());
        state.setGunLineTemp(r.gunLineTemp());
        state.setSoc(r.soc());
        state.setBatteryTemp(r.batteryMaxTemp());
        state.setTotalMinutes(r.totalChargeMinutes());
        state.setRemainMinutes(r.remainMinutes());
        state.setEnergy(r.chargedEnergy());
        state.setAmount(r.chargedAmount());
        state.setHardwareFault(r.hardwareFault());
        state.setTransactionNo(r.transactionNo());
        state.setUpdatedAt(LocalDateTime.now());
        return repository.save(state);
    }

    public List<GunStateEntity> byPile(String pileCode) {
        return repository.findByPileCodeOrderByGunNoAsc(pileCode);
    }

    public GunStateEntity get(String pileCode, String gunNo) {
        return repository.findByPileCodeAndGunNo(pileCode, gunNo).orElse(null);
    }
}