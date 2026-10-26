package app.adera.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RegistryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RegistryServiceApplication.class, args);
	}

}
