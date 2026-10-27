package app.framelink.cloud;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.invite-code=test-invite","spring.datasource.url=jdbc:h2:mem:http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","app.storage-dir=${java.io.tmpdir}/framelink-http-test"})
@AutoConfigureMockMvc
class HttpBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Test void missingLoginIsRejectedBeforeMultipartUpload() throws Exception {
        mvc.perform(multipart("/api/projects/nonexistent/media").file(new MockMultipartFile("file","photo.jpg","image/jpeg",new byte[]{1,2,3}))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }
    @Test void multibytePasswordCannotSilentlyTruncate() {
        assertThatThrownBy(()->auth.register("long@example.test","汉".repeat(40),"test-invite")).isInstanceOf(ApiProblem.class).hasMessageContaining("72");
    }
}
