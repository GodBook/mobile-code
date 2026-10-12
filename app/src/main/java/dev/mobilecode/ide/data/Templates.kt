package dev.mobilecode.ide.data

import dev.mobilecode.ide.core.CodeLanguage

object Templates {
    fun files(language: CodeLanguage, multiple: Boolean): Map<String,String> = when(language) {
        CodeLanguage.C -> if(multiple) mapOf(
            "src/main.c" to "#include <stdio.h>\n#include \"utils.h\"\n\nint main(void) {\n    int result = add(3, 5);\n    printf(\"Hello, Mobile Code! %d\\n\", result);\n    return 0;\n}\n",
            "src/utils.h" to "#ifndef UTILS_H\n#define UTILS_H\nint add(int a, int b);\n#endif\n",
            "src/utils.c" to "#include \"utils.h\"\n\nint add(int a, int b) {\n    return a + b;\n}\n"
        ) else mapOf("src/main.c" to "#include <stdio.h>\n\nint add(int a, int b) {\n    return a + b;\n}\n\nint main(void) {\n    int result = add(3, 5);\n    printf(\"Hello, Mobile Code! %d\\n\", result);\n    return 0;\n}\n")
        CodeLanguage.CPP -> if(multiple) mapOf(
            "src/main.cpp" to "#include <iostream>\n#include \"utils.hpp\"\n\nint main() {\n    int result = add(3, 5);\n    std::cout << \"Hello, Mobile Code! \" << result << std::endl;\n    return 0;\n}\n",
            "src/utils.hpp" to "#pragma once\nint add(int a, int b);\n",
            "src/utils.cpp" to "#include \"utils.hpp\"\n\nint add(int a, int b) {\n    return a + b;\n}\n"
        ) else mapOf("src/main.cpp" to "#include <iostream>\n#include <vector>\n\nint sum(const std::vector<int>& values) {\n    int result = 0;\n    for (int value : values) {\n        result += value;\n    }\n    return result;\n}\n\nint main() {\n    std::vector<int> values = {1, 2, 3};\n    int result = sum(values);\n    std::cout << \"Hello, Mobile Code! \" << result << std::endl;\n    return 0;\n}\n")
        CodeLanguage.PYTHON -> if(multiple) mapOf(
            "src/main.py" to "from utils import add\n\n\ndef main():\n    result = add(3, 5)\n    print(f\"Hello, Mobile Code! {result}\")\n\n\nif __name__ == \"__main__\":\n    main()\n",
            "src/utils.py" to "def add(a, b):\n    return a + b\n"
        ) else mapOf("src/main.py" to "def add(a, b):\n    return a + b\n\n\ndef main():\n    result = add(3, 5)\n    print(f\"Hello, Mobile Code! {result}\")\n\n\nif __name__ == \"__main__\":\n    main()\n")
        CodeLanguage.JAVA -> if(multiple) mapOf(
            "src/Main.java" to "public class Main {\n    public static void main(String[] args) {\n        int result = Utils.add(3, 5);\n        System.out.println(\"Hello, Mobile Code! \" + result);\n    }\n}\n",
            "src/Utils.java" to "public class Utils {\n    public static int add(int a, int b) {\n        return a + b;\n    }\n}\n"
        ) else mapOf("src/Main.java" to "public class Main {\n    static int add(int a, int b) {\n        return a + b;\n    }\n\n    public static void main(String[] args) {\n        int result = add(3, 5);\n        System.out.println(\"Hello, Mobile Code! \" + result);\n    }\n}\n")
    }
}
