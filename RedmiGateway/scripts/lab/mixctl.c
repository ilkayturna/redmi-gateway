/*
 * mixctl: minimal ALSA mixer control tool for diagnosing the Redmi's call audio routing.
 *
 *   mixctl list [substring]      print controls whose name contains substring
 *   mixctl get  <name>           print one control's values
 *   mixctl set  <name> <v>...    write integer/boolean values or an enum item name/index
 *
 * Built with the Android NDK and run as root over adb for lab work only; the gateway app
 * never ships or invokes it.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <sound/asound.h>

static int fd;

static int info_for(unsigned int numid, struct snd_ctl_elem_info *info) {
    memset(info, 0, sizeof(*info));
    info->id.numid = numid;
    return ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, info);
}

static void enum_name(struct snd_ctl_elem_info *info, unsigned int item, char *out, size_t len) {
    struct snd_ctl_elem_info tmp = *info;
    tmp.value.enumerated.item = item;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &tmp) == 0)
        snprintf(out, len, "%s", tmp.value.enumerated.name);
    else
        snprintf(out, len, "#%u", item);
}

static void print_values(struct snd_ctl_elem_info *info) {
    struct snd_ctl_elem_value val;
    memset(&val, 0, sizeof(val));
    val.id.numid = info->id.numid;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0) {
        printf("<unreadable>");
        return;
    }
    unsigned int n = info->count > 16 ? 16 : info->count;
    for (unsigned int i = 0; i < n; i++) {
        switch (info->type) {
        case SNDRV_CTL_ELEM_TYPE_BOOLEAN:
        case SNDRV_CTL_ELEM_TYPE_INTEGER:
            printf("%ld ", val.value.integer.value[i]);
            break;
        case SNDRV_CTL_ELEM_TYPE_INTEGER64:
            printf("%lld ", (long long)val.value.integer64.value[i]);
            break;
        case SNDRV_CTL_ELEM_TYPE_ENUMERATED: {
            char name[64];
            enum_name(info, val.value.enumerated.item[i], name, sizeof(name));
            printf("%s ", name);
            break;
        }
        default:
            printf("? ");
        }
    }
    if (info->count > 16) printf("... (%u values)", info->count);
}

static int find(const char *name, struct snd_ctl_elem_info *out) {
    struct snd_ctl_elem_list list;
    memset(&list, 0, sizeof(list));
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) return -1;
    unsigned int count = list.count;
    struct snd_ctl_elem_id *ids = calloc(count, sizeof(*ids));
    list.space = count;
    list.pids = ids;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) return -1;
    int found = -1;
    for (unsigned int i = 0; i < list.used; i++) {
        if (strcmp((const char *)ids[i].name, name) == 0) {
            info_for(ids[i].numid, out);
            found = 0;
            break;
        }
    }
    free(ids);
    return found;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: mixctl list [substr] | get <name> | set <name> <v>...\n");
        return 2;
    }
    fd = open("/dev/snd/controlC0", O_RDWR);
    if (fd < 0) {
        perror("open controlC0");
        return 1;
    }
    if (strcmp(argv[1], "list") == 0) {
        struct snd_ctl_elem_list list;
        memset(&list, 0, sizeof(list));
        ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list);
        unsigned int count = list.count;
        struct snd_ctl_elem_id *ids = calloc(count, sizeof(*ids));
        list.space = count;
        list.pids = ids;
        ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list);
        for (unsigned int i = 0; i < list.used; i++) {
            if (argc > 2 && !strstr((const char *)ids[i].name, argv[2])) continue;
            struct snd_ctl_elem_info info;
            if (info_for(ids[i].numid, &info) < 0) continue;
            printf("%u\t%s\t= ", ids[i].numid, ids[i].name);
            print_values(&info);
            printf("\n");
        }
        free(ids);
        return 0;
    }
    if (argc < 3) return 2;
    struct snd_ctl_elem_info info;
    if (find(argv[2], &info) < 0) {
        fprintf(stderr, "control not found: %s\n", argv[2]);
        return 1;
    }
    if (strcmp(argv[1], "get") == 0) {
        printf("%s = ", argv[2]);
        print_values(&info);
        printf("\n");
        return 0;
    }
    if (strcmp(argv[1], "set") == 0 && argc > 3) {
        struct snd_ctl_elem_value val;
        memset(&val, 0, sizeof(val));
        val.id.numid = info.id.numid;
        ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &val);
        for (int i = 3; i < argc && (unsigned int)(i - 3) < info.count; i++) {
            if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
                char *end;
                long idx = strtol(argv[i], &end, 10);
                if (*end != 0) {
                    idx = -1;
                    for (unsigned int k = 0; k < info.value.enumerated.items; k++) {
                        char name[64];
                        enum_name(&info, k, name, sizeof(name));
                        if (strcmp(name, argv[i]) == 0) { idx = k; break; }
                    }
                }
                if (idx < 0) { fprintf(stderr, "bad enum %s\n", argv[i]); return 1; }
                val.value.enumerated.item[i - 3] = (unsigned int)idx;
            } else if (info.type == SNDRV_CTL_ELEM_TYPE_INTEGER64) {
                val.value.integer64.value[i - 3] = atoll(argv[i]);
            } else {
                val.value.integer.value[i - 3] = atol(argv[i]);
            }
        }
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) < 0) {
            perror("write");
            return 1;
        }
        printf("%s := ", argv[2]);
        print_values(&info);
        printf("\n");
        return 0;
    }
    return 2;
}
