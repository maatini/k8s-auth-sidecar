package space.maatini.sidecar;

import com.styra.opa.wasm.OpaPolicy;

public class OpaApiExploreTest {
    public static void main(String[] args) throws Exception {
        OpaPolicy policy = OpaPolicy.builder().withPolicy(new byte[0]).build();
        System.out.println("OpaPolicy class found!");
    }
}
