package watoo.grd.nextroute.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

	/** PK (auto increment) */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 레코드 생성 시각 */
	@CreatedDate
	@Column(updatable = false)
	private LocalDateTime createdAt;

	/** 레코드 최종 수정 시각 */
	@LastModifiedDate
	private LocalDateTime updatedAt;

	/** 논리 삭제 시각 (null이면 활성) */
	private LocalDateTime deletedAt;

	public void markDeleted() {
		this.deletedAt = LocalDateTime.now();
	}
}
