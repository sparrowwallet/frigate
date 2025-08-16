package com.sparrowwallet.frigate.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeesMempoolEntry(double base, double ancestor) {
}
