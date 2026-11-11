package com.superbistro;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** End-to-end API flow against an in-memory database (H2 in PostgreSQL mode). */
@SpringBootTest
class OrderFlowTest {

    @Autowired
    WebApplicationContext ctx;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    private MockHttpSession signup(String restaurant, String email) throws Exception {
        TestOwners.create(ctx, restaurant, email, "secret123");
        MockHttpSession session = new MockHttpSession();
        var res = mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"secret123"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.role", is("OWNER")))
            .andReturn();
        // the controller rotates the session; continue with the new one
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private static String firstId(String json) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(json);
        if (!m.find()) throw new AssertionError("no id in " + json);
        return m.group(1);
    }

    @Test
    void apiRequiresLogin() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().is4xxClientError()); // no sign-up: Super Bistro only
        mvc.perform(get("/api/auth/config")).andExpect(status().isOk());
    }

    @Test
    void fullOrderFlowAndTenantIsolation() throws Exception {
        MockHttpSession a = signup("Bistro A", "owner-a@example.com");

        String menuJson = mvc.perform(post("/api/menu/import").session(a).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"items":[{"name":"Veg Fried Rice","price":110,"category":"Fried Rice","veg":true},
                              {"name":"Chicken Biryani","price":199,"category":"Biryani","veg":false}]}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.menu", hasSize(2)))
            .andReturn().getResponse().getContentAsString();
        String dishId = firstId(menuJson);

        // Offline dine-in order: 2 x first dish, 10 rupees discount, default 5% GST.
        String orderJson = mvc.perform(post("/api/orders").session(a).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"source":"OFFLINE","type":"DINE_IN","table":"T4","payment":"UPI","discount":10,
                     "items":[{"menuItemId":"%s","qty":2}]}
                    """.formatted(dishId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.no", is(1)))
            .andExpect(jsonPath("$.status", is("NEW")))
            .andExpect(jsonPath("$.table", is("T4")))
            .andReturn().getResponse().getContentAsString();
        String orderId = firstId(orderJson);

        // Swiggy order is numbered #2, forced to DELIVERY and PLATFORM payment.
        mvc.perform(post("/api/orders").session(a).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"source":"SWIGGY","platformId":"SW-991","payment":"CASH","items":[{"menuItemId":"%s","qty":1}]}
                    """.formatted(dishId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.no", is(2)))
            .andExpect(jsonPath("$.type", is("DELIVERY")))
            .andExpect(jsonPath("$.payment", is("PLATFORM")));

        mvc.perform(patch("/api/orders/" + orderId).session(a).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PREPARING\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("PREPARING")));

        mvc.perform(get("/api/orders").session(a))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/stats").session(a))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orders", is(2)))
            .andExpect(jsonPath("$.top[0].qty", is(3)))
            .andExpect(jsonPath("$.top[0].qtyBySource.OFFLINE", is(2)))
            .andExpect(jsonPath("$.top[0].qtyBySource.SWIGGY", is(1)))
            .andExpect(jsonPath("$.bySource.SWIGGY.orders", is(1)));

        mvc.perform(get("/api/stats").param("source", "ZOMATO").session(a))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orders", is(0)));

        // A second restaurant sees none of A's data and can't touch A's order or dishes.
        MockHttpSession b = signup("Bistro B", "owner-b@example.com");
        mvc.perform(get("/api/orders").session(b)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/menu").session(b)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(patch("/api/orders/" + orderId).session(b).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders").session(b).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"OFFLINE\",\"items\":[{\"menuItemId\":\"%s\",\"qty\":1}]}".formatted(dishId)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void totalsAreComputedOnTheServer() throws Exception {
        MockHttpSession s = signup("Bistro C", "owner-c@example.com");
        String dish = mvc.perform(post("/api/menu").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ghee Roast\",\"price\":120,\"category\":\"Breakfast\",\"veg\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // 3 x 120 = 360, minus 60 = 300, plus 5% GST = 315
        mvc.perform(post("/api/orders").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"OFFLINE\",\"type\":\"TAKEAWAY\",\"discount\":60,\"items\":[{\"menuItemId\":\"%s\",\"qty\":3}]}"
                    .formatted(firstId(dish))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subtotal", closeTo(360.0, 0.001)))
            .andExpect(jsonPath("$.gstAmt", closeTo(15.0, 0.001)))
            .andExpect(jsonPath("$.total", closeTo(315.0, 0.001)));
    }

    @Test
    void menuImportAddsAndUpdatesByName() throws Exception {
        MockHttpSession s = signup("Bistro E", "owner-e@example.com");
        mvc.perform(post("/api/menu").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Masala Dosa\",\"price\":70,\"category\":\"Breakfast\",\"veg\":true}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/menu/import").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"items":[
                      {"name":"  masala   dosa ","price":85,"category":"Breakfast","veg":true},
                      {"name":"Chicken 65","price":200,"category":"Starters","veg":false},
                      {"name":"Filter Coffee","price":30,"category":"Beverages"}
                    ]}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.added", is(2)))
            .andExpect(jsonPath("$.updated", is(1)))
            .andExpect(jsonPath("$.menu", hasSize(3)));

        mvc.perform(post("/api/menu/import").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"name\":\"Bad\",\"price\":-5}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void sectionRenameAndSoldOut() throws Exception {
        MockHttpSession s = signup("Bistro F", "owner-f@example.com");
        mvc.perform(post("/api/menu/import").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"items":[{"name":"Smiley","price":99,"category":"Bites"},{"name":"Veg Cutlet","price":99,"category":"Bites"},
                              {"name":"Lassi","price":49,"category":"Beverages"}]}"""))
            .andExpect(status().isOk());
        mvc.perform(post("/api/menu/category/rename").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"bites\",\"to\":\"Snacks\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.category == 'Snacks')]", hasSize(2)));
        mvc.perform(post("/api/menu/category/availability").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"Snacks\",\"active\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.active == false)]", hasSize(2)))
            .andExpect(jsonPath("$[?(@.name == 'Lassi' && @.active == true)]", hasSize(1)));
    }

    @Test
    void onlyTheMainAdminSeesSystemPage() throws Exception {
        // OrderFlowTest shares one database, so the main admin may be another test's owner;
        // what matters: a later sign-up is never main admin and is refused the System page.
        MockHttpSession later = signup("Bistro G", "owner-g@example.com");
        mvc.perform(get("/api/auth/me").session(later))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.platformAdmin", is(false)));
        mvc.perform(get("/api/system").session(later)).andExpect(status().isForbidden());
        mvc.perform(post("/api/system/backup/run").session(later).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void lineDetailsPriceDiscountAndNote() throws Exception {
        MockHttpSession s = signup("Bistro H", "owner-h@example.com");
        String menu = mvc.perform(post("/api/menu/import").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"name\":\"Paneer Chilli\",\"price\":159,\"category\":\"Starters\"}]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = firstId(menu);
        // Line 1: 2 x 150 (price changed from 159) with 10% off = 270, note "less spicy".
        // Line 2: same dish, 1 x 159 with Rs 9 off = 150.  Subtotal 420; GST 5% on 420 = 21; total 441.
        mvc.perform(post("/api/orders").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"source":"OFFLINE","items":[
                      {"menuItemId":"%s","qty":2,"price":150,"discount":10,"discountType":"PCT","note":"less spicy"},
                      {"menuItemId":"%s","qty":1,"discount":9,"discountType":"AMT"}]}""".formatted(id, id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.items[0].price", closeTo(150.0, 0.001)))
            .andExpect(jsonPath("$.items[0].menuPrice", closeTo(159.0, 0.001)))
            .andExpect(jsonPath("$.items[0].lineDiscount", closeTo(30.0, 0.001)))
            .andExpect(jsonPath("$.items[0].lineTotal", closeTo(270.0, 0.001)))
            .andExpect(jsonPath("$.items[0].note", is("less spicy")))
            .andExpect(jsonPath("$.items[1].lineTotal", closeTo(150.0, 0.001)))
            .andExpect(jsonPath("$.subtotal", closeTo(420.0, 0.001)))
            .andExpect(jsonPath("$.total", closeTo(441.0, 0.001)));
        mvc.perform(get("/api/stats").session(s))
            .andExpect(jsonPath("$.top[0].qty", is(3)))
            .andExpect(jsonPath("$.top[0].revenue", closeTo(420.0, 0.001)));
    }

    @Test
    void billDetailsAndCustomerMobile() throws Exception {
        MockHttpSession s = signup("Bistro M", "owner-m@example.com");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/restaurant").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bistro M\",\"gstPct\":0,\"address\":\"MG Road, Hyderabad\",\"phone\":\"8147284442\",\"gstin\":\"36abcde1234f1z5\",\"billFooter\":\"Thank you\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gstin", is("36ABCDE1234F1Z5")))
            .andExpect(jsonPath("$.address", is("MG Road, Hyderabad")));
        mvc.perform(get("/api/auth/me").session(s)).andExpect(jsonPath("$.restaurant.phone", is("8147284442")));

        String menu = mvc.perform(post("/api/menu/import").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"name\":\"Lassi\",\"price\":49,\"category\":\"Beverages\"}]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String dish = firstId(menu);
        mvc.perform(post("/api/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"OFFLINE\",\"type\":\"DINE_IN\",\"phone\":\"98765 43210\",\"items\":[{\"menuItemId\":\"" + dish + "\",\"qty\":1}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phone", is("98765 43210")));
        mvc.perform(post("/api/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"OFFLINE\",\"phone\":\"call me\",\"items\":[{\"menuItemId\":\"" + dish + "\",\"qty\":1}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void staffCannotEditMenu() throws Exception {
        MockHttpSession owner = signup("Bistro D", "owner-d@example.com");
        mvc.perform(post("/api/staff").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ravi\",\"email\":\"ravi@example.com\",\"password\":\"cashier123\",\"role\":\"STAFF\"}"))
            .andExpect(status().isOk());

        MockHttpSession staff = new MockHttpSession();
        var res = mvc.perform(post("/api/auth/login").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"RAVI@example.com\",\"password\":\"cashier123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.role", is("STAFF")))
            .andReturn();
        staff = (MockHttpSession) res.getRequest().getSession(false);

        mvc.perform(post("/api/menu").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"price\":1}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ravi@example.com\",\"password\":\"wrong-pass\"}"))
            .andExpect(status().isUnauthorized());
    }
}
