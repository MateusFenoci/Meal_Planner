package mealplanner;

import java.sql.*;
import java.util.*;
import java.io.*;

public class Main {
    private static final Scanner scanner = new Scanner(System.in);
    private static final List<String> DAYS_OF_WEEK = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
    private static final List<String> CATEGORIES = List.of("breakfast", "lunch", "dinner");
    private static int mealsId;
    private static int ingredientId;

    public static void main(String[] args) {
        try (Connection connection = initializeDatabaseConnection()) {
            setupDatabaseTables(connection);
            initializeIds(connection);

            String action;
            do {
                action = promptForAction();
                processAction(action, connection);
            } while (!action.equals("exit"));

        } catch (SQLException e) {
            throw new RuntimeException("Error connecting to the database", e);
        }
        exitProgram();
    }

    private static Connection initializeDatabaseConnection() throws SQLException {
        String dbUrl = "jdbc:postgresql:meals_db";
        String user = "postgres";
        String pass = "1111";
        return DriverManager.getConnection(dbUrl, user, pass);
    }

    private static void setupDatabaseTables(Connection connection) throws SQLException {
        String createMeals = "CREATE TABLE IF NOT EXISTS meals (meal_id INT PRIMARY KEY, category VARCHAR(50), meal VARCHAR(50))";
        String createIngredients = "CREATE TABLE IF NOT EXISTS ingredients (ingredient_id INT PRIMARY KEY, ingredient VARCHAR(50), meal_id INT REFERENCES meals(meal_id))";
        String createPlan = "CREATE TABLE IF NOT EXISTS plan (day_week VARCHAR(50), meal_option VARCHAR(50), meal_category VARCHAR(50), meal_id INTEGER)";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(createMeals);
            statement.executeUpdate(createIngredients);
            statement.executeUpdate(createPlan);
        }
    }

    private static void initializeIds(Connection connection) throws SQLException {
        mealsId = fetchMaxId(connection, "meals", "meal_id") + 1;
        ingredientId = fetchMaxId(connection, "ingredients", "ingredient_id") + 1;
    }

    private static int fetchMaxId(Connection connection, String tableName, String idColumn) throws SQLException {
        String query = "SELECT COALESCE(MAX(" + idColumn + "), 0) AS max_id FROM " + tableName;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            return resultSet.next() ? resultSet.getInt("max_id") : 0;
        }
    }

    private static void processAction(String action, Connection connection) throws SQLException {
        switch (action) {
            case "add" -> addMeal(connection);
            case "show" -> showMeals(connection, promptForCategoryToPrint());
            case "plan" -> planMeals(connection);
            case "list plan" -> listPlannedMeals(connection);
            case "save" -> handleSave(connection);
        }
    }

    private static void handleSave(Connection connection) throws SQLException {
        if (saveToFile(connection)) {
            System.out.println("Saved!");
        } else {
            System.out.println("Unable to save. Plan your meals first.");
        }
    }

    private static boolean saveToFile(Connection connection) throws SQLException {
        if (!hasPlannedMeals(connection)) return false;

        System.out.println("Input a filename:");
        String filename = scanner.nextLine();
        return saveIngredientsToFile(filename, connection);
    }

    private static boolean hasPlannedMeals(Connection connection) throws SQLException {
        String query = "SELECT COUNT(*) AS total FROM plan";
        try (PreparedStatement statement = connection.prepareStatement(query);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt("total") > 0;
        }
    }

    private static boolean saveIngredientsToFile(String filename, Connection connection) throws SQLException {
        String query = """
                SELECT i.ingredient AS ingredient_name, COUNT(*) AS quantity
                FROM plan p
                JOIN meals m ON p.meal_id = m.meal_id
                JOIN ingredients i ON m.meal_id = i.meal_id
                GROUP BY i.ingredient
                ORDER BY i.ingredient
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(query);
             ResultSet resultSet = preparedStatement.executeQuery();
             BufferedWriter writer = new BufferedWriter(new FileWriter(filename, false))) {

            while (resultSet.next()) {
                String ingredient = resultSet.getString("ingredient_name");
                int quantity = resultSet.getInt("quantity");
                writer.write((quantity > 1 ? ingredient + " x" + quantity : ingredient));
                writer.newLine();
            }

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void listPlannedMeals(Connection connection) throws SQLException {
        String query = """
                SELECT day_week, meal_category, meal_option
                FROM plan
                ORDER BY array_position(ARRAY['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'], day_week),
                         CASE meal_category WHEN 'breakfast' THEN 1 WHEN 'lunch' THEN 2 WHEN 'dinner' THEN 3 ELSE 4 END
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(query);
             ResultSet resultSet = preparedStatement.executeQuery()) {

            Map<String, Map<String, String>> mealPlan = new LinkedHashMap<>();
            while (resultSet.next()) {
                String day = resultSet.getString("day_week");
                String category = resultSet.getString("meal_category");
                String meal = resultSet.getString("meal_option");
                mealPlan.putIfAbsent(day, new LinkedHashMap<>());
                mealPlan.get(day).put(category, meal);
            }

            for (String day : DAYS_OF_WEEK) {
                Map<String, String> meals = mealPlan.getOrDefault(day, new HashMap<>());
                System.out.println(day);
                System.out.println("Breakfast: " + meals.getOrDefault("breakfast", "N/A"));
                System.out.println("Lunch: " + meals.getOrDefault("lunch", "N/A"));
                System.out.println("Dinner: " + meals.getOrDefault("dinner", "N/A"));
                System.out.println();
            }
        }
    }

    private static void planMeals(Connection connection) throws SQLException {
        for (String day : DAYS_OF_WEEK) {
            for (String category : CATEGORIES) {
                planMealForDayAndCategory(connection, day, category);
            }
            System.out.println("Yeah! We planned the meals for " + day + ".");
        }
        listPlannedMeals(connection);
    }

    private static void planMealForDayAndCategory(Connection connection, String day, String category) throws SQLException {
        String query = "SELECT meal FROM meals WHERE category = ? ORDER BY meal ASC";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, category);

            List<String> options = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                if (category.equals("breakfast")) {
                    System.out.println(day);
                }

                while (resultSet.next()) {
                    String meal = resultSet.getString("meal");
                    options.add(meal);
                    System.out.println(meal);
                }
            }

            String choice = promptForMealChoice(category, day, options);
            insertPlanEntry(connection, day, category, choice);
        }
    }

    private static void insertPlanEntry(Connection connection, String day, String category, String choice) throws SQLException {
        String getMealIdQuery = "SELECT meal_id FROM meals WHERE meal = ?";
        int mealId;

        try (PreparedStatement statement = connection.prepareStatement(getMealIdQuery)) {
            statement.setString(1, choice);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    mealId = resultSet.getInt("meal_id");
                } else {
                    return;
                }
            }
        }

        String insertQuery = "INSERT INTO plan (day_week, meal_option, meal_category, meal_id) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insertQuery)) {
            statement.setString(1, day);
            statement.setString(2, choice);
            statement.setString(3, category);
            statement.setInt(4, mealId);
            statement.executeUpdate();
        }
    }

    private static String promptForMealChoice(String category, String day, List<String> options) {
        System.out.println("Choose the " + category + " for " + day + " from the list above:");
        String choice;
        boolean isValidChoice;

        do {
            choice = scanner.nextLine();
            isValidChoice = options.contains(choice);

            if (!isValidChoice) {
                System.out.println("This meal doesn’t exist. Choose a meal from the list above.");
            }
        } while (!isValidChoice);

        return choice;
    }

    private static void addMeal(Connection connection) throws SQLException {
        String category = promptForCategory();
        String mealName = promptForMealName();
        String[] ingredients = promptForIngredients();

        String insertMealQuery = "INSERT INTO meals (meal_id, category, meal) VALUES (?, ?, ?)";
        String insertIngredientQuery = "INSERT INTO ingredients (ingredient_id, ingredient, meal_id) VALUES (?, ?, ?)";

        try (PreparedStatement mealStatement = connection.prepareStatement(insertMealQuery);
             PreparedStatement ingredientStatement = connection.prepareStatement(insertIngredientQuery)) {

            mealStatement.setInt(1, mealsId);
            mealStatement.setString(2, category);
            mealStatement.setString(3, mealName);
            mealStatement.executeUpdate();

            for (String ingredient : ingredients) {
                ingredientStatement.setInt(1, ingredientId++);
                ingredientStatement.setString(2, ingredient.trim());
                ingredientStatement.setInt(3, mealsId);
                ingredientStatement.executeUpdate();
            }

            System.out.println("The meal has been added!");
            mealsId++;
        }
    }

    private static String promptForCategory() {
        String category;
        do {
            System.out.println("Which meal do you want to add (breakfast, lunch, dinner)?");
            category = scanner.nextLine().trim();
            if (!CATEGORIES.contains(category)) {
                System.out.println("Wrong meal category! Choose from: breakfast, lunch, dinner.");
            }
        } while (!CATEGORIES.contains(category));
        return category;
    }

    private static String promptForMealName() {
        String mealName;
        do {
            System.out.println("Input the meal's name:");
            mealName = scanner.nextLine().trim();
            if (!mealName.matches("^[a-zA-Z ]+$")) {
                System.out.println("Wrong format. Use letters only!");
                mealName = null;
            }
        } while (mealName == null);
        return mealName;
    }

    private static String[] promptForIngredients() {
        String[] ingredients;
        do {
            System.out.println("Input the ingredients:");
            ingredients = scanner.nextLine().split(",");
            boolean isValid = Arrays.stream(ingredients).allMatch(i -> i.trim().matches("^[a-zA-Z ]+$"));

            if (!isValid || ingredients.length == 0) {
                System.out.println("Wrong format. Use letters only!");
                ingredients = null;
            }
        } while (ingredients == null);
        return ingredients;
    }

    private static String promptForAction() {
        String action;
        do {
            System.out.println("What would you like to do (add, show, plan, list plan, save, exit)?");
            action = scanner.nextLine().trim();
            if (!action.matches("add|show|plan|list plan|save|exit")) {
                System.out.println("Invalid action. Please enter 'add', 'show', 'plan', 'list plan', 'save' or 'exit'.");
            }
        } while (!action.matches("add|show|plan|list plan|save|exit"));
        return action;
    }

    private static String promptForCategoryToPrint() {
        String category;
        do {
            System.out.println("Which category do you want to print (breakfast, lunch, dinner)?");
            category = scanner.nextLine().trim();
            if (!CATEGORIES.contains(category)) {
                System.out.println("Wrong meal category! Choose from: breakfast, lunch, dinner.");
            }
        } while (!CATEGORIES.contains(category));
        return category;
    }

    private static void showMeals(Connection connection, String category) throws SQLException {
        String query = "SELECT * FROM meals WHERE category = ? ORDER BY meal_id";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, category);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.isBeforeFirst()) {
                    System.out.println("No meals found.");
                    return;
                }

                System.out.println("Category: " + category);
                while (resultSet.next()) {
                    String mealName = resultSet.getString("meal");
                    int mealId = resultSet.getInt("meal_id");
                    List<String> ingredients = fetchIngredientsForMeal(connection, mealId);

                    System.out.println("Name: " + mealName);
                    System.out.println("Ingredients:");
                    ingredients.forEach(System.out::println);
                }
            }
        }
    }

    private static List<String> fetchIngredientsForMeal(Connection connection, int mealId) throws SQLException {
        String query = "SELECT ingredient FROM ingredients WHERE meal_id = ? ORDER BY ingredient_id";
        List<String> ingredients = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, mealId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ingredients.add(resultSet.getString("ingredient"));
                }
            }
        }

        return ingredients;
    }

    private static void exitProgram() {
        System.out.println("Bye!");
    }
}
