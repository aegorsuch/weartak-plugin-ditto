// SPDX-License-Identifier: GPL-3.0-only
// Independently declared from the WearTAK core Community identity v1 wire specification.
package com.weartak.plugin.sdk.community.v1;

import android.os.Bundle;

interface ICommunityIdentityControlV1 {
    int getVersion();
    Bundle readCommunityIdentity();
}
