/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
#include "dns_answer.h"
#include <arpa/nameser.h>
#include <ctype.h>
#include <string.h>
#include <strings.h>

int aura_dns_hostname(const char *name) {
    if (!name) return 0;
    size_t length = strnlen(name, 255), label = 0;
    if (!length || length > 254) return 0;
    if (name[length - 1] == '.') --length;
    if (!length || length > 253) return 0;
    for (size_t i = 0; i < length; ++i) {
        unsigned char c = (unsigned char)name[i];
        if (c == '.') { if (!label || label > 63) return 0; label = 0; }
        else {
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '-' || c == '_')) return 0;
            ++label;
        }
    }
    return label > 0 && label <= 63;
}

static int equal_name(const char *a, const char *b) {
    size_t al = strlen(a), bl = strlen(b);
    if (al && a[al-1] == '.') --al;
    if (bl && b[bl-1] == '.') --bl;
    return al == bl && !strncasecmp(a, b, al);
}

int aura_dns_parse(const uint8_t *message, size_t length, const char *question,
                   int type, int rcode, struct aura_dns_answer *answer) {
    answer->count = 0;
    if (!aura_dns_hostname(question) || (type != ns_t_a && type != ns_t_aaaa) || length > 8192) return -1;
    ns_msg parsed;
    if (ns_initparse(message, (int)length, &parsed) < 0) return -2;
    if (!ns_msg_getflag(parsed, ns_f_qr) || ns_msg_getflag(parsed, ns_f_opcode) != ns_o_query ||
        ns_msg_getflag(parsed, ns_f_rcode) != rcode || rcode != ns_r_noerror) return -3;
    if (ns_msg_getflag(parsed, ns_f_tc)) return -4;
    if (ns_msg_count(parsed, ns_s_qd) != 1 || ns_msg_count(parsed, ns_s_an) > 64) return -5;
    ns_rr rr;
    if (ns_parserr(&parsed, ns_s_qd, 0, &rr) < 0 || ns_rr_class(rr) != ns_c_in ||
        (int)ns_rr_type(rr) != type || !equal_name(ns_rr_name(rr), question)) return -6;
    int total = ns_msg_count(parsed, ns_s_an);
    // Parse every RR first, so even an unrelated malformed record fails closed.
    for (int i = 0; i < total; ++i) if (ns_parserr(&parsed, ns_s_an, i, &rr) < 0) return -7;
    char owner[NS_MAXDNAME];
    if (strlen(question) >= sizeof(owner)) return -1;
    strcpy(owner, question);
    for (unsigned hop = 0; hop <= 8; ++hop) {
        char next[NS_MAXDNAME] = {0};
        unsigned count = 0;
        struct aura_dns_answer found = {0};
        for (int i = 0; i < total; ++i) {
            if (ns_parserr(&parsed, ns_s_an, i, &rr) < 0) return -7;
            if (ns_rr_class(rr) != ns_c_in || !equal_name(ns_rr_name(rr), owner)) continue;
            if (ns_rr_type(rr) == ns_t_cname) {
                char name[NS_MAXDNAME];
                int n = ns_name_uncompress(message, message + length, ns_rr_rdata(rr), name, sizeof(name));
                if (n < 0 || (size_t)n != ns_rr_rdlen(rr) || !aura_dns_hostname(name)) return -8;
                if (*next && !equal_name(next, name)) return -9;
                strcpy(next, name);
            } else if ((int)ns_rr_type(rr) == type) {
                size_t bytes = type == ns_t_a ? 4 : 16;
                if (ns_rr_rdlen(rr) != bytes) return -10;
                unsigned j;
                for (j = 0; j < count; ++j) if (!memcmp(found.address[j], ns_rr_rdata(rr), bytes)) break;
                if (j == count) {
                    if (count == AURA_DNS_ADDRESSES) return -11;
                    memcpy(found.address[count++], ns_rr_rdata(rr), bytes);
                }
            }
        }
        if (!*next) { found.count = count; *answer = found; return 0; }
        if (count || hop == 8 || equal_name(next, owner) || equal_name(next, question)) return -12;
        strcpy(owner, next);
    }
    return -12;
}
