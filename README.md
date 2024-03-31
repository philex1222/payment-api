# Payment API

This is a RESTful API for processing payments in a banking system.

## Technologies Used
- Java
- Spring Boot
- Spring Data JPA
- Spring Security
- JSON Web Tokens (JWT)
- H2 Database
- Swagger

## Getting Started

### Prerequisites
- Java Development Kit (JDK) 8 or higher
- Apache Maven

### Building the Application
1. Clone the repository: `git clone https://github.com/your-username/payment-api.git`
2. Navigate to the project directory: `cd payment-api`
3. Build the application: `mvn clean install`

### Running the Application
1. Run the application: `mvn spring-boot:run`
2. The application will start running on `http://localhost:8080`

### API Documentation
The API documentation is generated using Swagger. You can access the Swagger UI at `http://localhost:8080/swagger-ui.html` to explore and test the API endpoints.

### Authentication
The API uses JWT-based authentication. To access the protected endpoints, include the JWT token in the `Authorization` header of the request.

### API Endpoints

#### Payments
- `POST /api/payments`: Create a new payment
- `GET /api/payments/{id}`: Get a payment by ID
- `GET /api/payments/source-account?sourceAccount={sourceAccount}`: Get payments by source account
- `GET /api/payments/destination-account?destinationAccount={destinationAccount}`: Get payments by destination account
- `PUT /api/payments/{id}/status`: Update payment status

#### Authentication
- `POST /api/auth/login`: Generate a JWT token by providing username and password

### Testing
The project includes unit tests, integration tests, and end-to-end tests. To run the tests, use the following command: `mvn test`

## Contributing
Contributions are welcome! If you find any issues or have suggestions for improvements, please open an issue or submit a pull request.
