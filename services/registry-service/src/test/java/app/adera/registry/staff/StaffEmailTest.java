package app.adera.registry.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.adera.registry.common.Errors;
import org.junit.jupiter.api.Test;

class StaffEmailTest {

	@Test
	void anAddressLongerThanTheColumnIsRefusedNotAConflict() {
		assertThat(StaffService.normalise(" Person@Example.COM ")).isEqualTo("person@example.com");
		assertThatThrownBy(() -> StaffService.normalise("a@" + "b".repeat(255) + "." + "c".repeat(100))).isInstanceOf(Errors.ValidationFailed.class);
	}

}
