/* Implemented in airplay_jni.cpp: forwards service registration to NsdManager. GPL-3.0-or-later. */
#ifndef DNSSD_ANDROID_H
#define DNSSD_ANDROID_H

#ifdef __cplusplus
extern "C" {
#endif

void android_dnssd_register(const char *type, const char *name, unsigned short port,
                            const char **keys, const char **values, int count);
void android_dnssd_unregister(const char *type);

#ifdef __cplusplus
}
#endif
#endif
