package com.yuki.rubinco.watchpoint.persistence.repository;

import com.yuki.rubinco.watchpoint.persistence.entity.M_JapaneseTranslation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M_JapaneseTranslationRepository extends JpaRepository<M_JapaneseTranslation, Long> {

  Optional<M_JapaneseTranslation> findByLocalizationKey(String localizationKey);
}
