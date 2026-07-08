package com.settleflow;

import org.springframework.boot.SpringApplication;

public class TestSettleflowApplication {

	public static void main(String[] args) {
		SpringApplication.from(SettleflowApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
