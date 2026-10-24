/*
 * DNS-SD backend for Android, replacing UxPlay's dns_sd/ and mdnsd/ backends.
 *
 * UxPlay builds the _raop._tcp and _airplay._tcp TXT records; this backend hands
 * them to the Java side (NsdManager does the actual multicast announcing) and keeps
 * a DNS wire-format copy, which UxPlay returns to the client in GET /info.
 *
 * Copyright (C) 2026 airplay-tv contributors. GPL-3.0-or-later.
 */

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "uxplay/dnssd.h"
#include "uxplay/dnssdint.h"
#include "uxplay/global.h"
#include "uxplay/utils.h"
#include "dnssd_android.h"

#define MAX_TXT_ITEMS 24
#define MAX_TXT_BYTES 1024

typedef struct {
    int count;
    const char *keys[MAX_TXT_ITEMS];
    char values[MAX_TXT_ITEMS][128];
    unsigned char wire[MAX_TXT_BYTES];
    int wire_len;
} txt_t;

typedef struct dnssd_private_s {
    txt_t raop;
    txt_t airplay;
    char raop_name[256];
} dnssd_private_t;

static void txt_reset(txt_t *txt) {
    txt->count = 0;
    txt->wire_len = 0;
}

static int txt_add(txt_t *txt, const char *key, const char *value) {
    size_t klen = strlen(key), vlen = strlen(value);
    if (txt->count >= MAX_TXT_ITEMS || vlen >= sizeof(txt->values[0])) {
        return -1;
    }
    size_t item = klen + 1 + vlen;
    if (item > 255 || txt->wire_len + 1 + (int) item > MAX_TXT_BYTES) {
        return -1;
    }
    txt->keys[txt->count] = key;
    memcpy(txt->values[txt->count], value, vlen + 1);
    txt->count++;

    unsigned char *p = txt->wire + txt->wire_len;
    *p++ = (unsigned char) item;
    memcpy(p, key, klen);
    p[klen] = '=';
    memcpy(p + klen + 1, value, vlen);
    txt->wire_len += 1 + (int) item;
    return 0;
}

static void txt_publish(const char *type, const char *name, unsigned short port, const txt_t *txt) {
    const char *values[MAX_TXT_ITEMS];
    for (int i = 0; i < txt->count; i++) {
        values[i] = txt->values[i];
    }
    android_dnssd_register(type, name, port, txt->keys, values, txt->count);
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    if (error) *error = DNSSD_ERROR_NOERROR;
    dnssd_private_t *dnssd = (dnssd_private_t *) calloc(1, sizeof(dnssd_private_t));
    if (!dnssd) {
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    char hwaddr[2 * MAX_HWADDR_LEN + 1];
    if (utils_hwaddr_raop(hwaddr, sizeof(hwaddr), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_HWADDRLEN;
        return NULL;
    }
    snprintf(dnssd->raop_name, sizeof(dnssd->raop_name), "%s@%s", hwaddr, dnssd_public->name);
    return dnssd;
}

void dnssd_private_destroy(void *private) {
    free(private);
}

int dnssd_register_raop(dnssd_t *dnssd_public, unsigned short port) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    char features[22];
    const char *pw = "false";
    const char *sf = RAOP_SF;
    switch (dnssd_public->pin_pw) {
    case 1:
        pw = "true";
        sf = "0x8c";
        break;
    case 2:
    case 3:
        pw = "true";
        sf = "0x84";
        break;
    default:
        break;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);

    txt_t *t = &dnssd->raop;
    txt_reset(t);
    if (txt_add(t, "ch", RAOP_CH) || txt_add(t, "cn", RAOP_CN) || txt_add(t, "da", RAOP_DA) ||
        txt_add(t, "et", RAOP_ET) || txt_add(t, "vv", RAOP_VV) || txt_add(t, "ft", features) ||
        txt_add(t, "am", GLOBAL_MODEL) || txt_add(t, "md", RAOP_MD) || txt_add(t, "rhd", RAOP_RHD) ||
        txt_add(t, "pw", pw) || txt_add(t, "sr", RAOP_SR) || txt_add(t, "ss", RAOP_SS) ||
        txt_add(t, "sv", RAOP_SV) || txt_add(t, "tp", RAOP_TP) || txt_add(t, "txtvers", RAOP_TXTVERS) ||
        txt_add(t, "sf", sf) || txt_add(t, "vs", RAOP_VS) || txt_add(t, "vn", RAOP_VN) ||
        txt_add(t, "pk", dnssd_public->pk)) {
        return -1;
    }
    txt_publish("_raop._tcp", dnssd->raop_name, port, t);
    return 0;
}

int dnssd_register_airplay(dnssd_t *dnssd_public, unsigned short port) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22];
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);

    txt_t *t = &dnssd->airplay;
    txt_reset(t);
    if (txt_add(t, "deviceid", device_id) || txt_add(t, "features", features) ||
        txt_add(t, "pw", dnssd_public->pin_pw ? "true" : "false") || txt_add(t, "flags", "0x4") ||
        txt_add(t, "model", GLOBAL_MODEL) || txt_add(t, "pk", dnssd_public->pk) ||
        txt_add(t, "pi", AIRPLAY_PI) || txt_add(t, "srcvers", AIRPLAY_SRCVERS) ||
        txt_add(t, "vv", AIRPLAY_VV)) {
        return -1;
    }
    txt_publish("_airplay._tcp", dnssd_public->name, port, t);
    return 0;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    *length = dnssd->raop.wire_len;
    return (const char *) dnssd->raop.wire;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_private_t *dnssd = (dnssd_private_t *) dnssd_public->dnssd_private;
    *length = dnssd->airplay.wire_len;
    return (const char *) dnssd->airplay.wire;
}

void dnssd_unregister_raop(dnssd_t *dnssd_public) {
    android_dnssd_unregister("_raop._tcp");
}

void dnssd_unregister_airplay(dnssd_t *dnssd_public) {
    android_dnssd_unregister("_airplay._tcp");
}

/* NsdManager owns its own sockets: nothing for the caller to service. */
int dnssd_get_service_fd(dnssd_t *dnssd, int service) {
    return -1;
}

int dnssd_process_service(dnssd_t *dnssd, int service) {
    return 0;
}

void dnssd_error_text(int *error, const char *appname) {
}
