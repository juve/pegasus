#include <stdio.h>
#include <ctype.h>
#include <stdlib.h>
#include <string.h>

struct pstate {
    char arg[BUFSIZ];
    int argoff;
    char *input;
    int inputoff;
    int failed;
};

struct argument {
    char *value;
    struct argument *next;
};

static void error(struct pstate *s, char *message) {
    s->failed = 1;
}

static void add(struct pstate *s, char c) {
    int i = s->argoff;
    if (i >= BUFSIZ) {
        // Arg too large
        error(s, "Error: Argument too large");
        return;
    }
    s->arg[i] = c;
    s->argoff += 1;
}

static inline char consume(struct pstate *s) {
    // Advance the input and return the next character
    s->inputoff++;
    return s->input[s->inputoff];
}

static inline char next(struct pstate *s) {
    // Return the next character in the input
    return s->input[s->inputoff];
}

static inline void skipwhite(struct pstate *s) {
    // Skip until the next non-whitespace character
    char la0 = next(s);
    while (isspace(la0)) {
        la0 = consume(s);
    }
}

static inline int isident(char c) {
    // Return true if c is a valid environment variable name character
    if ((c >= '0' && c <= '9') ||
        (c >= 'A' && c <= 'Z') ||
        (c >= 'a' && c <= 'z') ||
        (c == '_')){
        return 1;
    }
    return 0;
}

void parseSubstitution(struct pstate *s) {
    char varname[BUFSIZ];
    char *v = varname;

    // Consume the starting $
    char la0 = consume(s);

    if (la0 == '{') {
        // Cosume the opening {
        la0 = consume(s);

        while (1) {
            if (la0 == '}') {
                // End of var name
                // Consume ending }
                consume(s);
                break;
            } else if (la0 == '\0') {
                // Unterminated substitution
                fprintf(stderr, "Unterminated variable substitution");
                return;
            } else {
                *v = la0;
                v++;
            }
            la0 = consume(s);
        }
    } else {
        while (1) {
            if (isident(la0)) {
                *v = la0;
                v++;
            } else if (la0 == '\0') {
                // End of input
                break;
            } else {
                // End of ident
                break;
            }
            la0 = consume(s);
        }
    }

    *v = '\0';

    // Get the value of the environment variable
    char *value = getenv(varname);

    // If it has no value, then just skip it
    if (value == NULL) {
        return;
    }

    // If it has a value, add all the characters to the current argument
    for (v = value; *v != '\0'; v++) {
        add(s, *v);
    }
}

void parseDoubleQuoted(struct pstate *s) {
    // Consume the starting "
    consume(s);

    char la0 = next(s);
    while (1) {
        if (la0 == '"') {
            // Consume the ending "
            consume(s);
            break;
        } else if (la0 == '\0') {
            // Unterminated string
            fprintf(stderr, "Unterminated double-quoted string");
            break;
        } else if (la0 == '$') {
            parseSubstitution(s);
        } else if (la0 == '\\') {
            // Consume the '\'
            la0 = consume(s);

            if (la0 == '\0') {
                // Unterminated escape
                fprintf(stderr, "Unterminated escape sequence");
                break;
            } else if (la0 == 't') {
                add(s, '\t');
            } else if (la0 == 'v') {
                add(s, '\v');
            } else if (la0 == 'a') {
                add(s, '\a');
            } else if (la0 == 'b') {
                add(s, '\b');
            } else if (la0 == 'n') {
                add(s, '\n');
            } else if (la0 == 'r') {
                add(s, '\r');
            } else {
                // Not an escape sequence, just add it
                add(s, '\\');
                add(s, la0);
            }
        } else {
            add(s, la0);
        }
        la0 = consume(s);
    }
}

void parseSingleQuoted(struct pstate *s) {
    // Consume the starting '
    char la0 = consume(s);

    while (1) {
        if (la0 == '\'') {
            // Consume the ending '
            consume(s);
            break;
        } else if (la0 == '\0') {
            // Unterminated string
            fprintf(stderr, "Unterminated single-quoted string");
            break;
        }

        add(s, la0);

        la0 = consume(s);
    }
}

void parseArg(struct pstate *s) {
    skipwhite(s);
    char la0 = next(s);
    while (1) {
        if (isspace(la0)) {
            // End of argument
            break;
        } else if (la0 == '\0') {
            // End of input
            break;
        } else if (la0 == '"') {
            parseDoubleQuoted(s);
        } else if (la0 == '\'') {
            parseSingleQuoted(s);
        } else if (la0 == '$') {
            parseSubstitution(s);
        } else {
            add(s, la0);
            consume(s);
        }
        la0 = next(s);
    }
    skipwhite(s);
    add(s, '\0');
}

struct argument *parseCommand(char *input) {
    struct argument *args = NULL;
    struct argument *p = NULL;

    struct pstate s;
    s.input = input;
    s.inputoff = 0;

    int i = 0;
    while (next(&s) != '\0') {
        s.argoff = 0;
        s.failed = 0;

        parseArg(&s);

        if (s.failed) {
            printf("Failed!\n");
            goto failure;
        }

        struct argument *new = (struct argument *)malloc(sizeof(struct argument));
        new->value = strdup(s.arg);
        new->next = NULL;

        if (p == NULL) {
            args = new;
            p = new;
        } else {
            p->next = new;
            p = p->next;
        }
    }

    return args;

failure:
    while (args != NULL) {
        p = args;
        args = args->next;
        free(p);
    }

    return NULL;
}

struct argument *parseArgs(int argc, char *argv[]) {
    for (int i=0; i<argc; i++) {
        struct pstate s;
        s.argoff = 0;
        s.input = argv[i];
        s.inputoff = 0;
        parseArg(&s);
    }
    return NULL;
}

void test(char *command) {
    printf("Testing: %s\n", command);
    struct argument *args = parseCommand(command);

    for (struct argument *a = args; a!=NULL; a = a->next) {
        printf("ARG: '%s'\n", a->value);
    }
}

int main(int argc, char *argv[]) {
    test("foo bar baz");
    test("foo    bar    baz");
    test("   foo  ");
    test("foo 'gideon juve'");
    test("foo \"gideon juve\"");
    test("foo ''");
    test("''");
    test("foo '\\t\\afoo bar' foo");
    test("\"foo \\t\\n\\t foo\" foo");
    test("\"foo \\p foo\"");
    test("$HOME");
    test("${HOME}FOO");
    test("${FROBNERBOB}FOO");
    return 0;
}
