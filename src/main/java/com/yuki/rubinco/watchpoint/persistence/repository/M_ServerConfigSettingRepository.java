package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.M_ServerConfigSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_ServerConfigSettingRepository extends JpaRepository<M_ServerConfigSetting, Long> {

  Optional<M_ServerConfigSetting> findBySettingKey(String settingKey);
}
