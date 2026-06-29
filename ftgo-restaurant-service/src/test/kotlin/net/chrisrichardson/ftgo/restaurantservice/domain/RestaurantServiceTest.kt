package net.chrisrichardson.ftgo.restaurantservice.domain

import net.chrisrichardson.ftgo.common.Address
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RestaurantServiceTest {

    private lateinit var svc: RestaurantService
    private lateinit var repo: RestaurantRepository
    private lateinit var eventPublisher: RestaurantDomainEventPublisher

    @Before fun setup() {
        repo           = mock(RestaurantRepository::class.java)
        eventPublisher = mock(RestaurantDomainEventPublisher::class.java)
        svc = RestaurantService()
        ReflectionTestUtils.setField(svc, "restaurantRepository", repo)
        ReflectionTestUtils.setField(svc, "restaurantDomainEventPublisher", eventPublisher)
    }

    private val menu = RestaurantMenu(listOf(MenuItem("1", "Vindaloo", mock(net.chrisrichardson.ftgo.common.Money::class.java))))
    private val address = Address("1 Main St", null, "Oakland", "CA", "94612")
    private val request = CreateRestaurantRequest("Ajanta", address, menu)

    @Test fun shouldCreateRestaurant() {
        svc.create(request)
        val captor = ArgumentCaptor.forClass(Restaurant::class.java)
        verify(repo).save(captor.capture())
        assertEquals("Ajanta", captor.value.name)
        verify(eventPublisher).publish(any(Restaurant::class.java), any())
    }

    @Test fun shouldFindById() {
        val restaurant = Restaurant("Ajanta", menu)
        given(repo.findById(1L)).willReturn(Optional.of(restaurant))
        val result = svc.findById(1L)
        assertTrue(result.isPresent)
        assertEquals("Ajanta", result.get().name)
    }

    @Test fun shouldReturnEmptyWhenNotFound() {
        given(repo.findById(99L)).willReturn(Optional.empty())
        assertTrue(svc.findById(99L).isEmpty)
    }
}
