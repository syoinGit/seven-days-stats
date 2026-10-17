package com.yuki.rubinco.watchpoint.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "M_PLAYER")
public class M_Player {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_path", length = 500)
  private String sourcePath;

  @Column(name = "player_key", nullable = false, unique = true, length = 180)
  private String playerKey;

  @Column(name = "platform", nullable = false, length = 40)
  private String platform;

  @Column(name = "user_id", nullable = false, length = 120)
  private String userId;

  @Column(name = "native_platform", length = 40)
  private String nativePlatform;

  @Column(name = "native_user_id", length = 120)
  private String nativeUserId;

  @Column(name = "player_name", nullable = false, length = 120)
  private String playerName;

  @Column(name = "first_seen_at")
  private LocalDateTime firstSeenAt;

  @Column(name = "last_seen_at")
  private LocalDateTime lastSeenAt;
}
