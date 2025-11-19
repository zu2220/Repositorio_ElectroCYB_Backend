package com.electrocyb.store.auth;

import com.electrocyb.store.auth.dto.*;
import com.electrocyb.store.email.EmailService;
import jakarta.mail.MessagingException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthenticationManager authenticationManager,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.emailService = emailService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new RuntimeException("El email ya está registrado");
        }

        User user = User.builder()
                .fullName(request.fullName())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .phone(request.phone())
                .role(Role.CUSTOMER)
                .build();

        userRepository.save(user);

        // 🔔 Enviar correo de bienvenida / confirmación de registro
        try {
            emailService.sendRegistrationConfirmation(
                    user.getEmail(),
                    user.getFullName()
            );
        } catch (MessagingException e) {
            // No romper el flujo si el correo falla
            System.out.println("Error al enviar correo de confirmación:");
            e.printStackTrace();
        }

        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, mapToDto(user));
    }

    public AuthResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.email(),
                        request.password()
                )
        );

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        // 🔔 Notificación de inicio de sesión
        try {
            emailService.sendLoginNotification(
                    user.getEmail(),
                    user.getFullName()
            );
        } catch (MessagingException e) {
            // Solo logueamos el error, pero NO rompemos el login
            e.printStackTrace();
        }

        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, mapToDto(user));
    }

    public UserDto mapToDto(User u) {
        return new UserDto(
                u.getId(),
                u.getFullName(),
                u.getEmail(),
                u.getPhone(),
                u.getRole().name()
        );
    }
}