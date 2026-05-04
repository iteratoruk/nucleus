package iterator.nucleus

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.commons.lang3.builder.CompareToBuilder
import org.apache.commons.lang3.builder.EqualsBuilder
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.util.ProxyUtils
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.Optional

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class AbstractJpaEntity : Comparable<AbstractJpaEntity> {
  companion object {
    private const val serialVersionUID = 1L
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: Long = 0

  @Version var version: Long = 0

  @CreatedBy
  @Column(updatable = false)
  var createdBy: String? = null

  @CreatedDate
  @Column(updatable = false)
  var createdDate: Instant? = null

  override fun equals(other: Any?): Boolean {
    other ?: return false
    if (this === other) return true
    if (javaClass != ProxyUtils.getUserClass(other)) return false
    other as AbstractJpaEntity
    return EqualsBuilder().append(id, other.id).isEquals
  }

  override fun hashCode(): Int = HashCodeBuilder().append(id).toHashCode()

  override fun toString(): String = "${javaClass.simpleName}($id)"

  override fun compareTo(other: AbstractJpaEntity): Int = CompareToBuilder().append(id, other.id).toComparison()
}

@MappedSuperclass
abstract class AbstractMutableJpaEntity : AbstractJpaEntity() {
  @LastModifiedBy var lastModifiedBy: String? = null

  @LastModifiedDate var lastModifiedDate: Instant? = null
}

@Component
class ClientIdAuditor : AuditorAware<String> {
  override fun getCurrentAuditor(): Optional<String> =
    Optional.ofNullable(
      RequestContextHolder
        .getRequestAttributes()
        ?.getAttribute(NucleusHeaders.CLIENT_ID, RequestAttributes.SCOPE_REQUEST)
        ?.toString(),
    )
}

@Component
class ClientIdRequestAttributeFilter : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    request.getHeader(NucleusHeaders.CLIENT_ID)?.let {
      request.setAttribute(NucleusHeaders.CLIENT_ID, it)
    }
    chain.doFilter(request, response)
  }
}

@NoRepositoryBean interface AbstractJpaRepository<T : AbstractJpaEntity> : JpaRepository<T, Long>
