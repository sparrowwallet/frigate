package com.sparrowwallet.frigate.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkInfo(int version, String subversion, boolean networkactive) {

}
