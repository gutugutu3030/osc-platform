oscSchema {
    message("/light/color") {
        description("set RGB color")
        scalar("r", INT)
        scalar("g", INT)
        scalar("b", INT)
    }

    message("/mesh/points") {
        description("set xyz points")
        scalar("pointCount", INT, role = LENGTH)
        array("points", lengthFrom = "pointCount") {
            tuple {
                field("x", INT)
                field("y", INT)
                field("z", FLOAT)
            }
        }
    }
}
