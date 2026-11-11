package com.superbistro;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Running table bill (add dishes later, kitchen rounds), cancel control, customer list. */
@SpringBootTest
class RunningBillAndCustomersTest {
    @Autowired WebApplicationContext ctx;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    private MockHttpSession login(String email, String password) throws Exception {
        var res = mvc.perform(post("/api/auth/login").session(new MockHttpSession()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private String body(MockHttpSession s, org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mvc.perform(rb).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String dish(MockHttpSession s, String name, int price) throws Exception {
        return JsonPath.read(body(s, post("/api/menu").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\",\"price\":" + price + ",\"category\":\"Mains\",\"veg\":true}")), "$.id");
    }

    @Test
    void runningBillCancelControlAndCustomers() throws Exception {
        TestOwners.create(ctx, "Running Bill Bistro", "rb-owner@example.com", "secret123");
        MockHttpSession owner = login("rb-owner@example.com", "secret123");
        mvc.perform(post("/api/staff").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ravi\",\"email\":\"rb-staff@example.com\",\"password\":\"staff1234\",\"role\":\"STAFF\"}"))
            .andExpect(status().isOk());
        MockHttpSession staff = login("rb-staff@example.com", "staff1234");
        String dosa = dish(owner, "Masala Dosa", 70);
        String coffee = dish(owner, "Filter Coffee", 30);

        // Table 4 orders, then asks for more: same order, second kitchen round, one bill.
        String order = body(staff, post("/api/orders").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"OFFLINE\",\"type\":\"DINE_IN\",\"table\":\"T4\",\"payment\":\"UNPAID\",\"customer\":\"Asha\","
                + "\"phone\":\"98765 43210\",\"items\":[{\"menuItemId\":\"" + dosa + "\",\"qty\":2}]}"));
        String id = JsonPath.read(order, "$.id");
        mvc.perform(patch("/api/orders/" + id).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"PREPARING\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/orders/" + id + "/items").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"menuItemId\":\"" + coffee + "\",\"qty\":2,\"note\":\"strong\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subtotal").value(200.0))
            .andExpect(jsonPath("$.total").value(210.0))
            .andExpect(jsonPath("$.round", is(2)))
            .andExpect(jsonPath("$.status", is("NEW")))
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.items[0].round", is(1)))
            .andExpect(jsonPath("$.items[1].round", is(2)))
            .andExpect(jsonPath("$.items[1].note", is("strong")));

        // Paid and completed: closed, nothing more can be added.
        mvc.perform(patch("/api/orders/" + id).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"payment\":\"UPI\",\"status\":\"COMPLETED\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/orders/" + id + "/items").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[{\"menuItemId\":\"" + coffee + "\",\"qty\":1}]}")).andExpect(status().isBadRequest());

        // Cancel control: staff can't, a reason is required, the day book lists it.
        String second = JsonPath.read(body(staff, post("/api/orders").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"OFFLINE\",\"type\":\"TAKEAWAY\",\"phone\":\"+91 9876543210\",\"items\":[{\"menuItemId\":\"" + coffee + "\",\"qty\":1}]}")), "$.id");
        mvc.perform(patch("/api/orders/" + second).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"reason\":\"Customer left\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/orders/" + second).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/orders/" + second).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"reason\":\"Customer left\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason", is("Customer left")));
        mvc.perform(patch("/api/orders/" + second).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"NEW\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/daybook").session(owner))
            .andExpect(jsonPath("$.cancelledOrders", hasSize(1)))
            .andExpect(jsonPath("$.cancelledOrders[0].reason", is("Customer left")))
            .andExpect(jsonPath("$.cancelledOrders[0].by", is("Owner")));

        // Customers: both spellings of the number are one customer; the cancelled order doesn't count.
        mvc.perform(get("/api/customers/lookup").param("phone", "9876543210").session(staff))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customer.name", is("Asha")))
            .andExpect(jsonPath("$.customer.visits", is(1)))
            .andExpect(jsonPath("$.customer.spent").value(210.0))
            .andExpect(jsonPath("$.favourites[0]", is("Filter Coffee")))
            .andExpect(jsonPath("$.recent", hasSize(2)));
        mvc.perform(get("/api/customers/lookup").param("phone", "1112223334").session(staff))
            .andExpect(status().isOk()).andExpect(jsonPath("$.customer").doesNotExist());
        mvc.perform(get("/api/customers").session(staff)).andExpect(status().isForbidden());
        mvc.perform(get("/api/customers").session(owner))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].key", is("9876543210")));
        mvc.perform(get("/api/customers/9876543210/orders").session(owner))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[1].items", is("2× Masala Dosa, 2× Filter Coffee")));
    }
}
