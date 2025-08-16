package com.sparrowwallet.frigate.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeeInfo(Double feerate, List<String> errors, int blocks) {

}
